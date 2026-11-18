//! Track sources. Each source decodes + filters on its own thread into bounded channels, so
//! opening/seeking/slow disks never stall the output clock.
use crate::api::TrackReq;
use anyhow::{anyhow, Context, Result};
use ffmpeg::{codec, filter, format, frame, media, ChannelLayout, Rational};
use ffmpeg_next as ffmpeg;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, sync_channel, Receiver, RecvTimeoutError, TryRecvError};
use std::sync::{Arc, Mutex};
use std::time::Instant;

pub const FPS: i32 = 30;
pub const RATE: i32 = 44100;
pub const NB: usize = 1024;
const VIDEO_BUFFER: usize = 60; // 2 s of decoded video per source

/// Filter graphs with drawtext/subtitles touch FreeType/libass, which crash when two threads
/// create or destroy fonts at the same time. Graph setup/teardown is rare: serialize it.
static GRAPH_LOCK: Mutex<()> = Mutex::new(());

#[derive(Clone)]
pub struct SrcCfg {
    pub w: u32,
    pub h: u32,
    pub qsv: bool,
    pub hwdec: bool,
    pub font: String,
}
type Cfg = SrcCfg;

#[derive(Debug)]
struct HwFail;
impl std::fmt::Display for HwFail {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { write!(f, "hardware decode failed") }
}
impl std::error::Error for HwFail {}

pub fn sanitize(s: &str) -> String {
    // drawtext text inside '...': keep all Unicode (titles are Japanese, "≫　A／B"), but
    // drop backslashes/control chars, use a typographic apostrophe, and escape % (expansion)
    s.chars()
        .filter(|c| *c != '\\' && !c.is_control())
        .map(|c| if c == '\'' { '\u{2019}'.to_string() } else if c == '%' { "\\%".to_string() } else { c.to_string() })
        .collect()
}

pub fn esc_path(p: &str) -> String {
    p.replace('\\', "\\\\").replace(':', "\\:").replace('\'', "\\'")
}


struct Source {
    req: TrackReq,
    ictx: format::context::Input,
    vidx: usize,
    vtb: Rational,
    vdec: ffmpeg::decoder::Video,
    aidx: Option<usize>,
    atb: Rational,
    adec: Option<ffmpeg::decoder::Audio>,
    ag: Option<filter::Graph>,
    vg: Option<filter::Graph>,
    sar: Rational,
    anchor_s: f64, // input time that maps to output position 0 of this source
    vq: VecDeque<frame::Video>,
    aq: VecDeque<frame::Audio>,
    eof: bool,
    using_hw: bool,
    got_video: u64,
    pkts: u64,
    duration_ms: i64,
}

impl Source {
    fn open(req: &TrackReq, hw: bool) -> Result<Source> {
        let mut ictx = format::input(&req.path).with_context(|| format!("open {}", req.path))?;
        let vs = ictx.streams().best(media::Type::Video).ok_or_else(|| anyhow!("no video stream"))?;
        let (vidx, vtb) = (vs.index(), vs.time_base());
        let vstart = if vs.start_time() != ffmpeg::ffi::AV_NOPTS_VALUE { vs.start_time() as f64 * f64::from(vtb) } else { 0.0 };
        let (vdec, using_hw) = open_video_decoder(&vs, hw)?;
        let sar = vdec.aspect_ratio();
        let (aidx, atb, adec) = match ictx.streams().best(media::Type::Audio) {
            Some(s) => match codec::context::Context::from_parameters(s.parameters()).and_then(|c| c.decoder().audio()) {
                Ok(d) => (Some(s.index()), s.time_base(), Some(d)),
                Err(_) => (None, Rational(1, 1), None), // undecodable audio: play with silence
            },
            None => (None, Rational(1, 1), None),
        };
        let start_s = req.start_ms as f64 / 1000.0;
        if req.start_ms > 0 {
            let ts = (start_s * 1_000_000.0) as i64;
            ictx.seek(ts, ..ts).ok();
        }
        let duration_ms = if ictx.duration() > 0 { ictx.duration() / 1000 } else { 0 };
        let anchor_s = vstart + start_s;
        let ag = match &adec { Some(d) => Some(audio_graph(d, req)?), None => None };
        Ok(Source {
            req: req.clone(), ictx, vidx, vtb, vdec, aidx, atb, adec, ag, vg: None, sar, anchor_s,
            vq: VecDeque::new(), aq: VecDeque::new(), eof: false, using_hw, got_video: 0, pkts: 0,
            duration_ms,
        })
    }

    fn push_video(&mut self, f: &mut frame::Video, cfg: &Cfg) -> Result<()> {
        self.got_video += 1;
        let Some(ts) = f.timestamp() else { return Ok(()) };
        let rel = ts as f64 * f64::from(self.vtb) - self.anchor_s;
        if rel < -0.001 { return Ok(()); } // before the requested start (keyframe seek)
        f.set_pts(Some((rel / f64::from(self.vtb)).round() as i64));
        if self.vg.is_none() {
            let _g = GRAPH_LOCK.lock().unwrap_or_else(|e| e.into_inner());
            self.vg = Some(video_graph(f, self.sar, self.vtb, &self.req, cfg)?);
        }
        let g = self.vg.as_mut().unwrap();
        g.get("in").unwrap().source().add(f)?;
        let mut o = frame::Video::empty();
        while g.get("out").unwrap().sink().frame(&mut o).is_ok() {
            self.vq.push_back(std::mem::replace(&mut o, frame::Video::empty()));
        }
        Ok(())
    }

    fn push_audio(&mut self, f: &mut frame::Audio) -> Result<()> {
        let (Some(g), Some(d)) = (self.ag.as_mut(), self.adec.as_ref()) else { return Ok(()) };
        let Some(ts) = f.timestamp() else { return Ok(()) };
        // timestamps relative to the anchor, in 1/sample_rate; aresample(async) pads/trims to them
        let rel = ts as f64 * f64::from(self.atb) - self.anchor_s;
        f.set_pts(Some((rel * d.rate() as f64).round() as i64));
        g.get("in").unwrap().source().add(f)?;
        let mut o = frame::Audio::empty();
        while g.get("out").unwrap().sink().frame(&mut o).is_ok() {
            self.aq.push_back(std::mem::replace(&mut o, frame::Audio::empty()));
        }
        Ok(())
    }

    /// Read one packet (or flush at the end). Returns false when everything is drained.
    fn pump(&mut self, cfg: &Cfg) -> Result<bool> {
        if self.eof { return Ok(false); }
        match self.ictx.packets().next() {
            Some((stream, packet)) => {
                self.pkts += 1;
                if self.using_hw && self.got_video == 0 && self.pkts > 400 { return Err(HwFail.into()); }
                if stream.index() == self.vidx {
                    if self.vdec.send_packet(&packet).is_err() && self.using_hw && self.got_video == 0 { return Err(HwFail.into()); }
                    let mut f = frame::Video::empty();
                    while self.vdec.receive_frame(&mut f).is_ok() { self.push_video(&mut f, cfg)?; }
                } else if Some(stream.index()) == self.aidx {
                    let d = self.adec.as_mut().unwrap();
                    let mut frames = vec![];
                    if d.send_packet(&packet).is_ok() {
                        let mut f = frame::Audio::empty();
                        while d.receive_frame(&mut f).is_ok() { frames.push(std::mem::replace(&mut f, frame::Audio::empty())); }
                    }
                    for mut f in frames { self.push_audio(&mut f)?; }
                }
                Ok(true)
            }
            None => {
                if self.using_hw && self.got_video == 0 { return Err(HwFail.into()); }
                self.vdec.send_eof().ok();
                let mut f = frame::Video::empty();
                while self.vdec.receive_frame(&mut f).is_ok() { self.push_video(&mut f, cfg)?; }
                if let Some(g) = self.vg.as_mut() {
                    g.get("in").unwrap().source().flush()?;
                    let mut o = frame::Video::empty();
                    while g.get("out").unwrap().sink().frame(&mut o).is_ok() { self.vq.push_back(std::mem::replace(&mut o, frame::Video::empty())); }
                }
                if let Some(d) = self.adec.as_mut() {
                    d.send_eof().ok();
                    let mut frames = vec![];
                    let mut f = frame::Audio::empty();
                    while d.receive_frame(&mut f).is_ok() { frames.push(std::mem::replace(&mut f, frame::Audio::empty())); }
                    for mut f in frames { self.push_audio(&mut f)?; }
                }
                if let Some(g) = self.ag.as_mut() {
                    g.get("in").unwrap().source().flush()?;
                    let mut o = frame::Audio::empty();
                    while g.get("out").unwrap().sink().frame(&mut o).is_ok() { self.aq.push_back(std::mem::replace(&mut o, frame::Audio::empty())); }
                }
                self.eof = true;
                Ok(false)
            }
        }
    }

}

fn video_graph(f: &frame::Video, sar: Rational, tb: Rational, req: &TrackReq, cfg: &Cfg) -> Result<filter::Graph> {
    let mut g = filter::Graph::new();
    let pix: ffmpeg::ffi::AVPixelFormat = f.format().into();
    let (sn, sd) = if sar.numerator() > 0 && sar.denominator() > 0 { (sar.numerator(), sar.denominator()) } else { (1, 1) };
    let args = format!("video_size={}x{}:pix_fmt={}:time_base={}/{}:pixel_aspect={}/{}",
        f.width(), f.height(), pix as i32, tb.numerator(), tb.denominator(), sn, sd);
    g.add(&filter::find("buffer").unwrap(), "in", &args)?;
    g.add(&filter::find("buffersink").unwrap(), "out", "")?;
    let (w, h) = (cfg.w, cfg.h);
    let mut chain = format!("yadif=deint=interlaced,scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps={FPS}:start_time=0");
    if let Some(s) = &req.subs {
        chain += &format!(",subtitles=filename='{}'", esc_path(s));
    }
    // the track title is drawn by the engine's post graph (one thread): FreeType is not safe to
    // use from several decode threads at once (crashed during pre-roll/crossfades)
    chain += if cfg.qsv { ",format=nv12" } else { ",format=yuv420p" };
    g.output("in", 0)?.input("out", 0)?.parse(&chain)?;
    g.validate()?;
    Ok(g)
}

fn audio_graph(dec: &ffmpeg::decoder::Audio, req: &TrackReq) -> Result<filter::Graph> {
    let mut g = filter::Graph::new();
    let layout = if dec.channel_layout().bits() != 0 { dec.channel_layout() } else { ChannelLayout::default(dec.channels() as i32) };
    let fmt: ffmpeg::ffi::AVSampleFormat = dec.format().into();
    let name = unsafe { std::ffi::CStr::from_ptr(ffmpeg::ffi::av_get_sample_fmt_name(fmt)) }.to_string_lossy().into_owned();
    let args = format!("time_base=1/{}:sample_rate={}:sample_fmt={}:channel_layout=0x{:x}",
        dec.rate(), dec.rate(), name, layout.bits());
    g.add(&filter::find("abuffer").unwrap(), "in", &args)?;
    g.add(&filter::find("abuffersink").unwrap(), "out", "")?;
    // async + first_pts=0: pad/trim so audio sample 0 lines up with video frame 0 (and fill gaps)
    let mut chain = format!("aresample={RATE}:async=1:first_pts=0,aformat=sample_fmts=fltp:channel_layouts=stereo");
    if req.gain_db.abs() > 0.01 { chain += &format!(",volume={:.2}dB", req.gain_db.clamp(-40.0, 25.0)); }
    chain += &format!(",asetnsamples=n={NB}:p=1");
    g.output("in", 0)?.input("out", 0)?.parse(&chain)?;
    g.validate()?;
    Ok(g)
}

/// Open QSV hardware decoding when eligible (8-bit 4:2:0 h264/hevc/vp9/mpeg2), else software.
fn open_video_decoder(stream: &format::stream::Stream, hw: bool) -> Result<(ffmpeg::decoder::Video, bool)> {
    let mut sw = codec::context::Context::from_parameters(stream.parameters())?;
    sw.set_threading(ffmpeg::threading::Config { kind: ffmpeg::threading::Type::Frame, count: 2 });
    let id = sw.id();
    let swdec = sw.decoder().video()?;
    let eligible = matches!(swdec.format(), format::Pixel::YUV420P | format::Pixel::YUVJ420P);
    let name = match id {
        codec::Id::H264 => Some("h264_qsv"),
        codec::Id::HEVC => Some("hevc_qsv"),
        codec::Id::VP9 => Some("vp9_qsv"),
        codec::Id::MPEG2VIDEO => Some("mpeg2_qsv"),
        _ => None,
    };
    if hw && eligible {
        if let Some(c) = name.and_then(ffmpeg::decoder::find_by_name) {
            let ctx = codec::context::Context::from_parameters(stream.parameters())?;
            if let Ok(opened) = ctx.decoder().open_as(c) {
                if let Ok(v) = opened.video() { return Ok((v, true)); }
            }
        }
    }
    Ok((swdec, false))
}


impl Drop for Source {
    fn drop(&mut self) {
        let _g = GRAPH_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        self.vg.take();
        self.ag.take();
    }
}

// ======================= threaded handle =======================

struct SendV(frame::Video);
unsafe impl Send for SendV {}
struct SendA(frame::Audio);
unsafe impl Send for SendA {}

#[derive(Clone, Debug)]
pub struct Info {
    pub duration_ms: i64,
    pub using_hw: bool,
}

pub enum Pull {
    Frame(frame::Video),
    Late,
    Eof,
}

/// A track playing on its own decode thread. Dropping the handle stops the thread.
pub struct Handle {
    pub req: TrackReq,
    vrx: Receiver<SendV>,
    arx: Receiver<SendA>,
    info: Arc<Mutex<Option<std::result::Result<Info, String>>>>,
    stop: Arc<AtomicBool>,
    pub frames_out: i64,
    pub announced: bool,
    eof: bool,
    /// samples owed: played as silence while this track's audio was late; dropped when it arrives
    debt: i64,
    has_audio: bool,
    pub audio_frames: i64,
    q: [VecDeque<f32>; 2],
    /// output sample index where this track's audio starts = its first video frame * 1470
    pub start_pos: Option<i64>,
    pub first_video_at: Option<i64>,
}

impl Drop for Handle {
    fn drop(&mut self) { self.stop.store(true, Ordering::SeqCst); }
}

impl Handle {
    pub fn spawn(req: TrackReq, cfg: SrcCfg) -> Handle {
        let (vtx, vrx) = sync_channel::<SendV>(VIDEO_BUFFER);
        let (atx, arx) = channel::<SendA>();
        let info = Arc::new(Mutex::new(None));
        let stop = Arc::new(AtomicBool::new(false));
        let (i2, s2, r2) = (info.clone(), stop.clone(), req.clone());
        std::thread::Builder::new().name("mediad-src".into()).spawn(move || {
            let mut hw = cfg.hwdec;
            'open: loop {
                let mut src = match Source::open(&r2, hw) {
                    Ok(s) => s,
                    Err(e) => { *i2.lock().unwrap() = Some(Err(format!("{e:#}"))); return; }
                };
                *i2.lock().unwrap() = Some(Ok(Info { duration_ms: src.duration_ms, using_hw: src.using_hw }));
                let mut sent = 0u64;
                loop {
                    if s2.load(Ordering::SeqCst) { return; }
                    match src.pump(&cfg) {
                        Ok(more) => {
                            // audio first (unbounded, never blocks), then video (bounded = backpressure)
                            while let Some(a) = src.aq.pop_front() { if atx.send(SendA(a)).is_err() { return; } }
                            while let Some(v) = src.vq.pop_front() {
                                if vtx.send(SendV(v)).is_err() { return; }
                                sent += 1;
                            }
                            if !more { return; } // end of track: dropping the senders signals EOF
                        }
                        Err(e) if e.downcast_ref::<HwFail>().is_some() && sent == 0 => {
                            eprintln!("hardware decode failed for {}, falling back to software", r2.path);
                            hw = false;
                            continue 'open;
                        }
                        Err(e) => { *i2.lock().unwrap() = Some(Err(format!("{e:#}"))); return; }
                    }
                }
            }
        }).expect("spawn source thread");
        Handle { req, vrx, arx, info, stop, frames_out: 0, announced: false, eof: false, debt: 0, has_audio: true, audio_frames: 0, q: [VecDeque::new(), VecDeque::new()], start_pos: None, first_video_at: None }
    }

    pub fn info(&self) -> Option<Info> { self.info.lock().unwrap().as_ref().and_then(|r| r.as_ref().ok().cloned()) }
    pub fn error(&self) -> Option<String> { self.info.lock().unwrap().as_ref().and_then(|r| r.as_ref().err().cloned()) }
    pub fn position_ms(&self) -> i64 { self.req.start_ms + self.frames_out * 1000 / FPS as i64 }
    pub fn remaining_ms(&self) -> Option<i64> {
        self.info().filter(|i| i.duration_ms > 0).map(|i| i.duration_ms - self.position_ms())
    }

    /// Next video frame, waiting at most until `deadline`.
    pub fn video(&mut self, deadline: Instant) -> Pull {
        if self.eof { return Pull::Eof; }
        let wait = deadline.saturating_duration_since(Instant::now());
        match self.vrx.recv_timeout(wait) {
            Ok(SendV(f)) => { self.frames_out += 1; Pull::Frame(f) }
            Err(RecvTimeoutError::Timeout) => Pull::Late,
            Err(RecvTimeoutError::Disconnected) => { self.eof = true; Pull::Eof }
        }
    }

    /// Pull decoded audio frames into the sample queue (waits briefly for audio that is just late).
    fn fill(&mut self, need: usize) {
        if !self.has_audio { return; }
        let deadline = Instant::now() + std::time::Duration::from_millis(15);
        while self.q[0].len() < need + self.debt as usize {
            let got = match self.arx.try_recv() {
                Ok(SendA(a)) => Some(a),
                Err(TryRecvError::Disconnected) => { self.has_audio = false; None }
                Err(TryRecvError::Empty) => match self.arx.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
                    Ok(SendA(a)) => Some(a),
                    Err(RecvTimeoutError::Disconnected) => { self.has_audio = false; None }
                    Err(RecvTimeoutError::Timeout) => None,
                },
            };
            let Some(a) = got else { break };
            let n = a.samples();
            for ch in 0..2 { self.q[ch].extend(&a.plane::<f32>(ch)[..n]); }
            self.audio_frames += 1;
        }
        // pay back samples that were played as silence while this track's audio was late
        let pay = (self.debt as usize).min(self.q[0].len());
        if pay > 0 {
            for ch in 0..2 { self.q[ch].drain(..pay); }
            self.debt -= pay as i64;
        }
    }

    /// This track's contribution to the output samples [pos, pos+n): silence before its start
    /// position, then its own samples. Missing (late) samples become silence + debt.
    pub fn take(&mut self, pos: i64, n: usize, out: &mut [Vec<f32>; 2]) {
        let start = self.start_pos.unwrap_or(i64::MAX);
        let lead = (start - pos).clamp(0, n as i64) as usize;
        for ch in 0..2 { out[ch].clear(); out[ch].resize(lead, 0.0); }
        let want = n - lead;
        if want == 0 { return; }
        self.fill(want);
        let have = self.q[0].len().min(want);
        for ch in 0..2 {
            out[ch].extend(self.q[ch].drain(..have));
            out[ch].resize(n, 0.0);
        }
        if have < want && self.has_audio { self.debt += (want - have) as i64; }
    }

    /// How far this track's audio is behind its video right now (ms).
    pub fn audio_lag_ms(&self) -> i64 { self.debt * 1000 / RATE as i64 }

}
