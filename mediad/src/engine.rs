//! The real-time engine (pull model).
//!
//! Every tick produces exactly one output video frame and then pulls just enough audio to keep
//! audio == video (in samples). Tracks are `Source`s that decode + filter on demand, so two of
//! them can run at once: that gives pre-roll (the next track is opened before the current one
//! ends) and crossfade transitions. Output timestamps come from counters, never from inputs.
//!
//! Per-source graph:  decode -> yadif -> scale/pad -> fps -> subtitles -> title -> format
//! Output path:       [blend A/B during transition] -> post graph (zmq, live text) -> overlay -> encoder
use crate::api::{Shared, TrackReq};
use crate::overlay::{Latest, OverlayCfg};
use anyhow::{anyhow, Context, Result};
use ffmpeg::{codec, encoder, filter, format, frame, media, ChannelLayout, Dictionary, Rational};
use ffmpeg_next as ffmpeg;
use serde_json::json;
use std::collections::VecDeque;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const FPS: i32 = 30;
pub const RATE: i32 = 44100;
const NB: usize = 1024; // audio samples per frame (AAC frame size)
const LEAD: Duration = Duration::from_millis(200);

pub struct Cfg {
    pub url: String,
    pub qsv: bool,
    pub hwdec: bool,
    pub w: u32,
    pub h: u32,
    pub font: String,
    pub live_file: Option<String>,
    pub ending_ms: i64,
    pub xfade_ms: i64,
    pub zmq: Option<String>,
    pub overlay: Option<OverlayCfg>,
}

#[derive(Debug)]
struct HwFail;
impl std::fmt::Display for HwFail {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { write!(f, "hardware decode failed") }
}
impl std::error::Error for HwFail {}

fn is_net(url: &str) -> bool { url.starts_with("rtmp://") || url.starts_with("rtmps://") }

fn open_muxer(url: &str) -> Result<format::context::Output> {
    if is_net(url) {
        let mut o = Dictionary::new();
        o.set("rtmp_live", "live");
        o.set("rw_timeout", "5000000");
        Ok(format::output_as_with(url, "flv", o)?)
    } else {
        Ok(format::output(url)?)
    }
}

fn sanitize(s: &str) -> String {
    // drawtext: keep it simple and safe (', :, %, \ and friends are special)
    s.chars().map(|c| if c.is_alphanumeric() || c == ' ' || c == '-' || c == '.' || c == '(' || c == ')' { c } else { ' ' }).collect()
}

fn esc_path(p: &str) -> String {
    p.replace('\\', "\\\\").replace(':', "\\:").replace('\'', "\\'")
}

fn writable_v(f: &mut frame::Video) { unsafe { ffmpeg::ffi::av_frame_make_writable(f.as_mut_ptr()); } }
fn writable_a(f: &mut frame::Audio) { unsafe { ffmpeg::ffi::av_frame_make_writable(f.as_mut_ptr()); } }

/// Plane geometry (bytes per row, rows) for the two pixel formats we produce.
fn planes(nv12: bool, w: usize, h: usize) -> Vec<(usize, usize)> {
    if nv12 { vec![(w, h), (w, h / 2)] } else { vec![(w, h), (w / 2, h / 2), (w / 2, h / 2)] }
}

// ======================= Source =======================

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
    frames_out: i64,
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
            frames_out: 0, duration_ms,
        })
    }

    fn position_ms(&self) -> i64 { self.req.start_ms + self.frames_out * 1000 / FPS as i64 }
    fn remaining_ms(&self) -> Option<i64> {
        if self.duration_ms > 0 { Some(self.duration_ms - self.position_ms()) } else { None }
    }

    fn push_video(&mut self, f: &mut frame::Video, cfg: &Cfg) -> Result<()> {
        self.got_video += 1;
        let Some(ts) = f.timestamp() else { return Ok(()) };
        let rel = ts as f64 * f64::from(self.vtb) - self.anchor_s;
        if rel < -0.001 { return Ok(()); } // before the requested start (keyframe seek)
        f.set_pts(Some((rel / f64::from(self.vtb)).round() as i64));
        if self.vg.is_none() { self.vg = Some(video_graph(f, self.sar, self.vtb, &self.req, cfg)?); }
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

    fn next_video(&mut self, cfg: &Cfg) -> Result<Option<frame::Video>> {
        while self.vq.is_empty() {
            if !self.pump(cfg)? { break; }
        }
        let f = self.vq.pop_front();
        if f.is_some() { self.frames_out += 1; }
        Ok(f)
    }

    /// One 1024-sample audio frame, or None if this source has no more audio right now.
    fn next_audio(&mut self, cfg: &Cfg) -> Result<Option<frame::Audio>> {
        if self.ag.is_none() { return Ok(None); }
        // don't read far ahead for audio: video drives the demuxer
        let mut budget = 64;
        while self.aq.is_empty() && budget > 0 {
            if !self.pump(cfg)? { break; }
            budget -= 1;
        }
        Ok(self.aq.pop_front())
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
    if std::path::Path::new(&cfg.font).exists() && !req.title.is_empty() {
        chain += &format!(",drawtext=fontfile={}:text='{}':x=2:y=2:fontsize=24:fontcolor=white:box=1:boxcolor=black:boxborderw=4",
            cfg.font, sanitize(&req.title));
    }
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

// ======================= Engine =======================

pub struct Engine {
    cfg: Cfg,
    sh: Arc<Shared>,
    octx: format::context::Output,
    venc: encoder::Video,
    aenc: encoder::Audio,
    v_idx: usize,
    a_idx: usize,
    v_tb: Rational,
    a_tb: Rational,
    vpts: i64,
    apts: i64,
    t0: Instant,
    connected: bool,
    last_try: Instant,
    force_key: bool,
    cur: Option<Source>,
    next: Option<Source>,
    xfade_from: Option<(i64, i64)>, // (first output frame of the transition, length in frames)
    filler_v: Option<filter::Graph>,
    filler_a: Option<filter::Graph>,
    post: Option<filter::Graph>,
    overlay: Option<Latest>,
    ending_sent: bool,
    idle: bool,
}

impl Engine {
    pub fn new(cfg: Cfg, sh: Arc<Shared>) -> Result<Engine> {
        let mut octx = open_muxer(&cfg.url)?;
        let global = octx.format().flags().contains(format::Flags::GLOBAL_HEADER);
        let vcodec = encoder::find_by_name(if cfg.qsv { "h264_qsv" } else { "libx264" }).ok_or_else(|| anyhow!("video encoder not found"))?;
        let mut v = codec::context::Context::new_with_codec(vcodec).encoder().video()?;
        v.set_width(cfg.w);
        v.set_height(cfg.h);
        v.set_format(if cfg.qsv { format::Pixel::NV12 } else { format::Pixel::YUV420P });
        v.set_time_base(Rational(1, FPS));
        v.set_frame_rate(Some(Rational(FPS, 1)));
        v.set_bit_rate(4_500_000);
        v.set_max_bit_rate(5_500_000);
        v.set_gop(60);
        if global { v.set_flags(codec::Flags::GLOBAL_HEADER); }
        let mut vo = Dictionary::new();
        vo.set("preset", if cfg.qsv { "medium" } else { "veryfast" });
        if cfg.qsv {
            for (k, val) in [("look_ahead", "1"), ("look_ahead_depth", "20"), ("adaptive_i", "1"), ("adaptive_b", "1"), ("bf", "2"), ("bufsize", "4500000")] {
                vo.set(k, val);
            }
        }
        let venc = v.open_with(vo)?;
        let mut vost = octx.add_stream(vcodec)?;
        vost.set_parameters(&venc);
        let v_idx = vost.index();

        let acodec = encoder::find(codec::Id::AAC).ok_or_else(|| anyhow!("aac encoder not found"))?;
        let mut a = codec::context::Context::new_with_codec(acodec).encoder().audio()?;
        a.set_rate(RATE);
        a.set_channel_layout(ChannelLayout::STEREO);
        a.set_format(format::Sample::F32(format::sample::Type::Planar));
        a.set_bit_rate(320_000);
        a.set_time_base(Rational(1, RATE));
        if global { a.set_flags(codec::Flags::GLOBAL_HEADER); }
        let aenc = a.open_as(acodec)?;
        let mut aost = octx.add_stream(acodec)?;
        aost.set_parameters(&aenc);
        let a_idx = aost.index();

        octx.write_header()?;
        let v_tb = octx.stream(v_idx).unwrap().time_base();
        let a_tb = octx.stream(a_idx).unwrap().time_base();
        let overlay = match &cfg.overlay { Some(o) => Some(crate::overlay::listen(o)?), None => None };
        sh.st.lock().unwrap().connected = true;
        sh.emit(json!({"type": "connected"}));
        let mut e = Engine {
            cfg, sh, octx, venc, aenc, v_idx, a_idx, v_tb, a_tb, vpts: 0, apts: 0, t0: Instant::now(),
            connected: true, last_try: Instant::now(), force_key: false, cur: None, next: None, xfade_from: None,
            filler_v: None, filler_a: None, post: None, overlay, ending_sent: false, idle: false,
        };
        e.post = e.post_graph()?;
        Ok(e)
    }

    // ---------- output ----------
    fn write(&mut self, mut pkt: ffmpeg::Packet, video: bool) {
        if !self.connected {
            self.try_reconnect();
            if !self.connected { return; }
        }
        let (idx, from, to) = if video { (self.v_idx, Rational(1, FPS), self.v_tb) } else { (self.a_idx, Rational(1, RATE), self.a_tb) };
        pkt.set_stream(idx);
        pkt.rescale_ts(from, to);
        if let Err(e) = pkt.write_interleaved(&mut self.octx) {
            eprintln!("write failed: {e}");
            self.connected = false;
            self.last_try = Instant::now();
            self.sh.st.lock().unwrap().connected = false;
            self.sh.emit(json!({"type": "disconnected", "error": e.to_string()}));
        }
    }

    fn try_reconnect(&mut self) {
        if self.last_try.elapsed() < Duration::from_secs(2) { return; }
        self.last_try = Instant::now();
        let r = (|| -> Result<()> {
            let mut o = open_muxer(&self.cfg.url)?;
            let mut vs = o.add_stream(encoder::find_by_name(if self.cfg.qsv { "h264_qsv" } else { "libx264" }))?;
            vs.set_parameters(&self.venc);
            let mut as_ = o.add_stream(encoder::find(codec::Id::AAC))?;
            as_.set_parameters(&self.aenc);
            o.write_header()?;
            self.v_tb = o.stream(self.v_idx).unwrap().time_base();
            self.a_tb = o.stream(self.a_idx).unwrap().time_base();
            self.octx = o;
            Ok(())
        })();
        match r {
            Ok(()) => {
                self.connected = true;
                self.force_key = true;
                self.sh.st.lock().unwrap().connected = true;
                self.sh.emit(json!({"type": "connected", "reconnect": true}));
            }
            Err(e) => eprintln!("reconnect failed: {e:#}"),
        }
    }

    fn drain(&mut self, video: bool) {
        loop {
            let mut pkt = ffmpeg::Packet::empty();
            let ok = if video { self.venc.receive_packet(&mut pkt).is_ok() } else { self.aenc.receive_packet(&mut pkt).is_ok() };
            if !ok { break; }
            self.write(pkt, video);
        }
    }

    /// Real-time pacing. If we fell far behind (slow reconnect, slow open), re-anchor instead of bursting.
    fn pace(&mut self) {
        let target = self.t0 + Duration::from_secs_f64(self.vpts as f64 / FPS as f64);
        let now = Instant::now();
        if now + LEAD < target {
            std::thread::sleep(target - now - LEAD);
        } else if now > target + Duration::from_secs(2) {
            self.t0 += now - target;
        }
    }

    fn post_graph(&self) -> Result<Option<filter::Graph>> {
        let have_font = std::path::Path::new(&self.cfg.font).exists();
        let mut parts = vec![];
        if let Some(z) = &self.cfg.zmq { parts.push(format!("zmq=bind_address='{}'", z.replace(':', "\\:"))); }
        if let Some(lf) = &self.cfg.live_file {
            // drawtext refuses to start on a missing textfile; the live service creates it later
            if !std::path::Path::new(lf).exists() { let _ = std::fs::write(lf, " "); }
        }
        if let (Some(lf), true) = (&self.cfg.live_file, have_font) {
            parts.push(format!("drawtext@live=fontfile={}:textfile='{}':reload=1:x=2:y=H-th:fontsize=24:fontcolor=white:box=1:boxcolor=black@0.4:boxborderw=4",
                self.cfg.font, esc_path(lf)));
        } else if self.cfg.zmq.is_some() && have_font {
            // a named, initially empty text slot that can be driven over zmq
            parts.push(format!("drawtext@live=fontfile={}:text=' ':x=2:y=H-th:fontsize=24:fontcolor=white:box=1:boxcolor=black@0.4:boxborderw=4", self.cfg.font));
        }
        if parts.is_empty() { return Ok(None); }
        let mut g = filter::Graph::new();
        let pix = if self.cfg.qsv { ffmpeg::ffi::AVPixelFormat::AV_PIX_FMT_NV12 } else { ffmpeg::ffi::AVPixelFormat::AV_PIX_FMT_YUV420P };
        let args = format!("video_size={}x{}:pix_fmt={}:time_base=1/{FPS}:pixel_aspect=1/1", self.cfg.w, self.cfg.h, pix as i32);
        g.add(&filter::find("buffer").unwrap(), "in", &args)?;
        g.add(&filter::find("buffersink").unwrap(), "out", "")?;
        let fmt = if self.cfg.qsv { "nv12" } else { "yuv420p" };
        g.output("in", 0)?.input("out", 0)?.parse(&format!("{},format={fmt}", parts.join(",")))?;
        g.validate()?;
        Ok(Some(g))
    }

    fn blend_overlay(&self, f: &mut frame::Video) {
        let Some(latest) = &self.overlay else { return };
        let Some(ov) = latest.lock().unwrap().clone() else { return };
        let o = self.cfg.overlay.as_ref().unwrap();
        let (fw, fh) = (self.cfg.w as usize, self.cfg.h as usize);
        writable_v(f);
        let nv12 = self.cfg.qsv;
        let ys = f.stride(0);
        {
            let y = f.data_mut(0);
            for r in 0..ov.h {
                let fy = o.y + r;
                if fy >= fh { break; }
                for c in 0..ov.w {
                    let fx = o.x + c;
                    if fx >= fw { break; }
                    let i = r * ov.w + c;
                    let a = ov.a[i] as u32;
                    if a == 0 { continue; }
                    let p = &mut y[fy * ys + fx];
                    *p = ((*p as u32 * (255 - a) + ov.y[i] as u32 * a) / 255) as u8;
                }
            }
        }
        let cs = f.stride(1);
        for r in (0..ov.h).step_by(2) {
            let fy = (o.y + r) / 2;
            if fy >= fh / 2 { break; }
            for c in (0..ov.w).step_by(2) {
                let fx = (o.x + c) / 2;
                if fx >= fw / 2 { break; }
                let i = r * ov.w + c;
                let a = ov.a[i] as u32;
                if a == 0 { continue; }
                let mix = |p: &mut u8, v: u8| *p = ((*p as u32 * (255 - a) + v as u32 * a) / 255) as u8;
                if nv12 {
                    let uv = f.data_mut(1);
                    mix(&mut uv[fy * cs + 2 * fx], ov.u[i]);
                    mix(&mut uv[fy * cs + 2 * fx + 1], ov.v[i]);
                } else {
                    mix(&mut f.data_mut(1)[fy * cs + fx], ov.u[i]);
                    let vs = f.stride(2);
                    mix(&mut f.data_mut(2)[fy * vs + fx], ov.v[i]);
                }
            }
        }
    }

    fn emit_video(&mut self, mut f: frame::Video) -> Result<()> {
        self.pace();
        f.set_pts(Some(self.vpts));
        if let Some(g) = self.post.as_mut() {
            g.get("in").unwrap().source().add(&f)?;
            let mut o = frame::Video::empty();
            if g.get("out").unwrap().sink().frame(&mut o).is_ok() { f = o; }
        }
        self.blend_overlay(&mut f);
        f.set_pts(Some(self.vpts));
        self.vpts += 1;
        f.set_kind(if self.force_key { ffmpeg::picture::Type::I } else { ffmpeg::picture::Type::None });
        self.force_key = false;
        self.venc.send_frame(&f)?;
        self.drain(true);
        Ok(())
    }

    fn emit_audio(&mut self, mut f: frame::Audio) -> Result<()> {
        f.set_pts(Some(self.apts));
        self.apts += f.samples() as i64;
        self.aenc.send_frame(&f)?;
        self.drain(false);
        Ok(())
    }

    fn silence(&mut self) -> Result<frame::Audio> {
        if self.filler_a.is_none() {
            let mut ga = filter::Graph::new();
            ga.parse(&format!("anullsrc=r={RATE}:cl=stereo:nb_samples={NB},aformat=sample_fmts=fltp:channel_layouts=stereo,abuffersink@out"))?;
            ga.validate()?;
            self.filler_a = Some(ga);
        }
        let g = self.filler_a.as_mut().unwrap();
        let mut a = frame::Audio::empty();
        g.get("out").or_else(|| g.get("abuffersink@out")).ok_or_else(|| anyhow!("filler audio sink"))?.sink().frame(&mut a)?;
        Ok(a)
    }

    fn black(&mut self) -> Result<frame::Video> {
        if self.filler_v.is_none() {
            let mut g = filter::Graph::new();
            let fmt = if self.cfg.qsv { "nv12" } else { "yuv420p" };
            g.parse(&format!("color=c=black:s={}x{}:r={FPS},format={fmt},buffersink@out", self.cfg.w, self.cfg.h))?;
            g.validate()?;
            self.filler_v = Some(g);
        }
        let g = self.filler_v.as_mut().unwrap();
        let mut v = frame::Video::empty();
        g.get("out").or_else(|| g.get("buffersink@out")).ok_or_else(|| anyhow!("filler video sink"))?.sink().frame(&mut v)?;
        Ok(v)
    }

    /// Audio for the frame just emitted: keep apts within one audio frame of vpts.
    fn audio_for_tick(&mut self, mix: Option<f32>) -> Result<()> {
        while (self.apts as f64) < self.vpts as f64 * RATE as f64 / FPS as f64 {
            let a = match self.cur.as_mut() { Some(s) => next_audio_safe(s, &self.cfg), None => None };
            let mut a = match a { Some(a) => a, None => self.silence()? };
            if let Some(t) = mix {
                let b = match self.next.as_mut() { Some(s) => next_audio_safe(s, &self.cfg), None => None };
                let b = match b { Some(b) => b, None => self.silence()? };
                writable_a(&mut a);
                let n = a.samples().min(b.samples());
                for ch in 0..2 {
                    let bp: Vec<f32> = b.plane::<f32>(ch)[..n].to_vec();
                    let ap = a.plane_mut::<f32>(ch);
                    for i in 0..n { ap[i] = ap[i] * (1.0 - t) + bp[i] * t; }
                }
            }
            self.emit_audio(a)?;
        }
        let (v_ms, a_ms) = (self.vpts * 1000 / FPS as i64, self.apts * 1000 / RATE as i64);
        self.sh.out_ms.store(v_ms, Ordering::Relaxed);
        self.sh.av_ms.store(a_ms - v_ms, Ordering::Relaxed);
        Ok(())
    }

    // ---------- track bookkeeping ----------
    fn started(&mut self, s: &Source) {
        {
            let mut st = self.sh.st.lock().unwrap();
            st.current = Some(s.req.clone());
            st.duration_ms = s.duration_ms;
            st.filler = false;
        }
        self.idle = false;
        self.sh.emit(json!({"type": "track_started", "path": s.req.path, "title": s.req.title,
            "start_ms": s.req.start_ms, "duration_ms": s.duration_ms, "hwdec": s.using_hw}));
    }

    fn ended(&mut self, req: &TrackReq, reason: &str) {
        self.sh.emit(json!({"type": "track_ended", "path": req.path, "reason": reason}));
    }

    /// Open the next queued track (with error reporting). Skips over broken tracks.
    fn open_queued(&mut self) -> Option<Source> {
        while let Some(req) = self.sh.pop_track() {
            match Source::open(&req, self.cfg.hwdec) {
                Ok(s) => return Some(s),
                Err(e) => {
                    self.sh.emit(json!({"type": "track_error", "path": req.path, "message": format!("{e:#}")}));
                    self.ended(&req, "error");
                }
            }
        }
        None
    }

    fn take_next_as_current(&mut self) {
        self.xfade_from = None;
        self.ending_sent = false;
        self.cur = self.next.take();
        if self.cur.is_none() { self.cur = self.open_queued(); }
        if let Some(s) = self.cur.take() {
            self.started(&s);
            self.cur = Some(s);
        }
    }

    // ---------- main loop ----------
    pub fn run(&mut self) -> Result<()> {
        loop {
            // control: skip cuts immediately (no transition); a pre-rolled next is kept
            if self.sh.skip.swap(false, Ordering::SeqCst) {
                if let Some(s) = self.cur.take() { self.ended(&s.req, "skipped"); }
                // "now" loads are pushed to the queue front; a stale pre-roll must not win over them
                if self.xfade_from.is_some() {
                    // mid-transition the next track is already playing: just promote it
                    self.cur = self.next.take();
                    self.xfade_from = None;
                    self.ending_sent = false;
                } else {
                    self.take_next_as_current();
                }
            }
            if self.sh.clear_next.swap(false, Ordering::SeqCst) {
                // /clear: forget a pre-rolled next track too (a "now" load follows)
                if self.next.take().is_some() && self.xfade_from.take().is_some() {
                    // the transition was cut short; the current track keeps playing alone
                }
            }
            let sk = self.sh.seek_ms.swap(-1, Ordering::SeqCst);
            if sk >= 0 {
                if let Some(s) = self.cur.take() {
                    let mut req = s.req.clone();
                    req.start_ms = sk;
                    match Source::open(&req, self.cfg.hwdec) {
                        Ok(n) => { self.started(&n); self.cur = Some(n); self.ending_sent = false; }
                        Err(e) => {
                            self.sh.emit(json!({"type": "track_error", "path": req.path, "message": format!("{e:#}")}));
                            self.ended(&req, "error");
                        }
                    }
                    self.xfade_from = None;
                }
            }
            if self.cur.is_none() { self.take_next_as_current(); }

            if self.cur.is_none() {
                // nothing to play: black + silence, still paced
                if !self.idle {
                    self.idle = true;
                    {
                        let mut st = self.sh.st.lock().unwrap();
                        st.filler = true;
                        st.current = None;
                    }
                    self.sh.emit(json!({"type": "idle"}));
                }
                let v = self.black()?;
                self.emit_video(v)?;
                self.audio_for_tick(None)?;
                continue;
            }

            // events + pre-roll based on the remaining time of the current track
            let remaining = self.cur.as_ref().unwrap().remaining_ms();
            let pos = self.cur.as_ref().unwrap().position_ms();
            self.sh.pos_ms.store(pos, Ordering::Relaxed);
            if let Some(rem) = remaining {
                if !self.ending_sent && rem <= self.cfg.ending_ms {
                    self.ending_sent = true;
                    let p = self.cur.as_ref().unwrap().req.path.clone();
                    self.sh.emit(json!({"type": "track_ending", "path": p, "remaining_ms": rem}));
                }
                let preroll = self.cfg.xfade_ms.max(3000);
                if self.next.is_none() && rem <= preroll { self.next = self.open_queued(); }
                if self.xfade_from.is_none() && self.next.is_some() && self.cfg.xfade_ms > 0 && rem <= self.cfg.xfade_ms {
                    let len = (rem * FPS as i64 / 1000).max(1);
                    self.xfade_from = Some((self.vpts, len));
                    let n = self.next.take().unwrap();
                    self.started(&n); // the next track is now audible/visible
                    self.next = Some(n);
                }
            }

            // video for this tick
            let a = match next_video_safe(self.cur.as_mut().unwrap(), &self.cfg) {
                Ok(v) => v,
                Err(e) => {
                    let s = self.cur.take().unwrap();
                    self.sh.emit(json!({"type": "track_error", "path": s.req.path, "message": format!("{e:#}")}));
                    self.ended(&s.req, "error");
                    continue;
                }
            };
            let Some(mut a) = a else {
                // current track finished
                let s = self.cur.take().unwrap();
                self.ended(&s.req, "eof");
                if self.xfade_from.is_some() {
                    // transition already announced the next track: just promote it
                    self.cur = self.next.take();
                    self.xfade_from = None;
                    self.ending_sent = false;
                } else {
                    self.take_next_as_current();
                }
                continue;
            };
            let mut mix = None;
            if let Some((from, len)) = self.xfade_from {
                let t = (((self.vpts - from) as f32 + 1.0) / (len as f32 + 1.0)).clamp(0.0, 1.0);
                if let Some(Some(b)) = self.next.as_mut().map(|n| next_video_safe(n, &self.cfg).ok().flatten()) {
                    blend(&mut a, &b, t, self.cfg.qsv, self.cfg.w as usize, self.cfg.h as usize);
                }
                mix = Some(t);
            }
            self.emit_video(a)?;
            self.audio_for_tick(mix)?;
        }
    }
}

/// Like Source::next_video, but on a hardware decode failure reopen the same track in software.
fn next_video_safe(s: &mut Source, cfg: &Cfg) -> Result<Option<frame::Video>> {
    match s.next_video(cfg) {
        Err(e) if e.downcast_ref::<HwFail>().is_some() => {
            eprintln!("hardware decode failed for {}, falling back to software", s.req.path);
            let frames_out = s.frames_out;
            *s = Source::open(&s.req.clone(), false)?;
            s.frames_out = frames_out;
            s.next_video(cfg)
        }
        r => r,
    }
}

fn next_audio_safe(s: &mut Source, cfg: &Cfg) -> Option<frame::Audio> {
    s.next_audio(cfg).ok().flatten()
}

/// Crossfade b over a in place: a = a*(1-t) + b*t, plane by plane.
fn blend(a: &mut frame::Video, b: &frame::Video, t: f32, nv12: bool, w: usize, h: usize) {
    writable_v(a);
    let k = (t * 256.0) as u32;
    for (p, (row_bytes, rows)) in planes(nv12, w, h).into_iter().enumerate() {
        let (sa, sb) = (a.stride(p), b.stride(p));
        let bd = b.data(p);
        let ad = a.data_mut(p);
        for r in 0..rows {
            let ar = &mut ad[r * sa..r * sa + row_bytes];
            let br = &bd[r * sb..r * sb + row_bytes];
            for (x, y) in ar.iter_mut().zip(br) {
                *x = ((*x as u32 * (256 - k) + *y as u32 * k) >> 8) as u8;
            }
        }
    }
}
