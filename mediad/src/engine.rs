//! The real-time engine: one clock, one encoder pair, one muxer. Tracks come and go;
//! the output never restarts. Output timestamps come from counters, never from inputs.
use crate::api::{Shared, TrackReq};
use anyhow::{anyhow, Context, Result};
use ffmpeg::{codec, encoder, filter, format, frame, media, ChannelLayout, Dictionary, Rational};
use ffmpeg_next as ffmpeg;
use serde_json::json;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const FPS: i32 = 30;
pub const RATE: i32 = 44100;
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
}

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
    fv: Option<filter::Graph>,
    fa: Option<filter::Graph>,
}

enum Outcome { Eof, Skipped, Seek(i64), HwFail }

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
    s.chars().map(|c| if c.is_ascii_alphanumeric() || c == ' ' || c == '-' { c } else { ' ' }).collect()
}

fn esc_path(p: &str) -> String {
    p.replace('\\', "\\\\").replace(':', "\\:").replace('\'', "\\'")
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
            vo.set("look_ahead", "1");
            vo.set("look_ahead_depth", "20");
            vo.set("adaptive_i", "1");
            vo.set("adaptive_b", "1");
            vo.set("bf", "2");
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
        sh.st.lock().unwrap().connected = true;
        sh.emit(json!({"type": "connected"}));
        Ok(Engine { cfg, sh, octx, venc, aenc, v_idx, a_idx, v_tb, a_tb, vpts: 0, apts: 0,
            t0: Instant::now(), connected: true, last_try: Instant::now(), force_key: false, fv: None, fa: None })
    }

    // ---------- output side ----------
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

    /// Real-time pacing. If we fell far behind (e.g. a slow reconnect), re-anchor instead of bursting.
    fn pace(&mut self, due: Duration) {
        let target = self.t0 + due;
        let now = Instant::now();
        if now + LEAD < target {
            std::thread::sleep(target - now - LEAD);
        } else if now > target + Duration::from_secs(2) {
            self.t0 += now - target;
        }
    }

    fn send_video(&mut self, f: &mut frame::Video) -> Result<()> {
        self.pace(Duration::from_secs_f64(self.vpts as f64 / FPS as f64));
        f.set_pts(Some(self.vpts));
        self.vpts += 1;
        if self.force_key {
            f.set_kind(ffmpeg::picture::Type::I);
            self.force_key = false;
        }
        self.venc.send_frame(f)?;
        self.drain(true);
        Ok(())
    }

    fn send_audio(&mut self, f: &mut frame::Audio) -> Result<()> {
        self.pace(Duration::from_secs_f64(self.apts as f64 / RATE as f64));
        f.set_pts(Some(self.apts));
        self.apts += f.samples() as i64;
        self.aenc.send_frame(f)?;
        self.drain(false);
        Ok(())
    }

    fn pull_video(&mut self, g: &mut filter::Graph) -> Result<()> {
        let mut f = frame::Video::empty();
        while g.get("out").unwrap().sink().frame(&mut f).is_ok() { self.send_video(&mut f)?; }
        Ok(())
    }
    fn pull_audio(&mut self, g: &mut filter::Graph) -> Result<()> {
        let mut f = frame::Audio::empty();
        while g.get("out").unwrap().sink().frame(&mut f).is_ok() { self.send_audio(&mut f)?; }
        Ok(())
    }

    // ---------- filler (nothing queued): black + silence, still paced ----------
    fn filler_slice(&mut self) -> Result<()> {
        if self.fv.is_none() {
            let mut g = filter::Graph::new();
            let fmt = if self.cfg.qsv { "nv12" } else { "yuv420p" };
            g.parse(&format!("color=c=black:s={}x{}:r={FPS},format={fmt},buffersink@out", self.cfg.w, self.cfg.h))?;
            g.validate()?;
            self.fv = Some(g);
            let mut ga = filter::Graph::new();
            ga.parse(&format!("anullsrc=r={RATE}:cl=stereo:nb_samples=1024,aformat=sample_fmts=fltp:channel_layouts=stereo,abuffersink@out"))?;
            ga.validate()?;
            self.fa = Some(ga);
        }
        let (mut fv, mut fa) = (self.fv.take().unwrap(), self.fa.take().unwrap());
        let r = (|| -> Result<()> {
            for _ in 0..FPS {
                let mut v = frame::Video::empty();
                fv.get("out").or_else(|| fv.get("buffersink@out")).ok_or_else(|| anyhow!("filler video sink missing"))?.sink().frame(&mut v)?;
                self.send_video(&mut v)?;
                while (self.apts as f64) < self.vpts as f64 * RATE as f64 / FPS as f64 {
                    let mut a = frame::Audio::empty();
                    fa.get("out").or_else(|| fa.get("abuffersink@out")).ok_or_else(|| anyhow!("filler audio sink missing"))?.sink().frame(&mut a)?;
                    self.send_audio(&mut a)?;
                }
            }
            Ok(())
        })();
        self.fv = Some(fv);
        self.fa = Some(fa);
        r
    }

    // ---------- main loop ----------
    pub fn run(&mut self) -> Result<()> {
        loop {
            match self.sh.pop_track() {
                Some(mut req) => {
                    self.sh.skip.store(false, Ordering::SeqCst);
                    self.sh.seek_ms.store(-1, Ordering::SeqCst);
                    self.sh.st.lock().unwrap().filler = false;
                    self.play(&mut req);
                }
                None => {
                    {
                        let mut st = self.sh.st.lock().unwrap();
                        if !st.filler {
                            st.filler = true;
                            st.current = None;
                            drop(st);
                            self.sh.emit(json!({"type": "idle"}));
                        }
                    }
                    self.filler_slice()?;
                }
            }
        }
    }

    fn play(&mut self, req: &mut TrackReq) {
        let mut hw = self.cfg.hwdec;
        loop {
            match self.play_once(req, hw) {
                Ok(Outcome::Eof) => { self.ended(req, "eof"); break; }
                Ok(Outcome::Skipped) => { self.ended(req, "skipped"); break; }
                Ok(Outcome::Seek(ms)) => { req.start_ms = ms; continue; }
                Ok(Outcome::HwFail) => {
                    eprintln!("hardware decode failed for {}, falling back to software", req.path);
                    self.sh.emit(json!({"type": "hwdec_fallback", "path": req.path}));
                    hw = false;
                    continue;
                }
                Err(e) => {
                    self.sh.emit(json!({"type": "track_error", "path": req.path, "message": format!("{e:#}")}));
                    self.ended(req, "error");
                    break;
                }
            }
        }
    }

    fn ended(&mut self, req: &TrackReq, reason: &str) {
        self.sh.emit(json!({"type": "track_ended", "path": req.path, "reason": reason}));
        self.sh.st.lock().unwrap().current = None;
    }

    fn video_graph(&self, f: &frame::Video, sar: Rational, tb: Rational, req: &TrackReq) -> Result<filter::Graph> {
        let mut g = filter::Graph::new();
        let pix: ffmpeg::ffi::AVPixelFormat = f.format().into();
        let (sn, sd) = if sar.numerator() > 0 && sar.denominator() > 0 { (sar.numerator(), sar.denominator()) } else { (1, 1) };
        let args = format!("video_size={}x{}:pix_fmt={}:time_base={}/{}:pixel_aspect={}/{}",
            f.width(), f.height(), pix as i32, tb.numerator(), tb.denominator(), sn, sd);
        g.add(&filter::find("buffer").unwrap(), "in", &args)?;
        g.add(&filter::find("buffersink").unwrap(), "out", "")?;
        let (w, h) = (self.cfg.w, self.cfg.h);
        let have_font = std::path::Path::new(&self.cfg.font).exists();
        let mut chain = format!("yadif=deint=interlaced,scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps={FPS}");
        if let Some(s) = &req.subs {
            chain += &format!(",subtitles=filename='{}'", esc_path(s));
        }
        if have_font {
            chain += &format!(",drawtext=fontfile={}:text='{}':x=2:y=2:fontsize=24:fontcolor=white:box=1:boxcolor=black:boxborderw=4",
                self.cfg.font, sanitize(&req.title));
            if let Some(lf) = &self.cfg.live_file {
                chain += &format!(",drawtext=fontfile={}:textfile='{}':reload=1:x=2:y=H-th:fontsize=24:fontcolor=white:box=1:boxcolor=black@0.4:boxborderw=4",
                    self.cfg.font, esc_path(lf));
            }
        }
        chain += if self.cfg.qsv { ",format=nv12" } else { ",format=yuv420p" };
        g.output("in", 0)?.input("out", 0)?.parse(&chain)?;
        g.validate()?;
        Ok(g)
    }

    fn audio_graph(&self, dec: &ffmpeg::decoder::Audio, tb: Rational, req: &TrackReq, debt: i64) -> Result<filter::Graph> {
        let mut g = filter::Graph::new();
        let layout = if dec.channel_layout().bits() != 0 { dec.channel_layout() } else { ChannelLayout::default(dec.channels() as i32) };
        let fmt: ffmpeg::ffi::AVSampleFormat = dec.format().into();
        let name = unsafe { std::ffi::CStr::from_ptr(ffmpeg::ffi::av_get_sample_fmt_name(fmt)) }.to_string_lossy().into_owned();
        let args = format!("time_base={}/{}:sample_rate={}:sample_fmt={}:channel_layout=0x{:x}",
            tb.numerator(), tb.denominator(), dec.rate(), name, layout.bits());
        g.add(&filter::find("abuffer").unwrap(), "in", &args)?;
        g.add(&filter::find("abuffersink").unwrap(), "out", "")?;
        let mut chain = format!("aresample={RATE},aformat=sample_fmts=fltp:channel_layouts=stereo");
        // keep audio locked to video: drop leading samples if audio ran ahead, add silence if behind
        if debt > 0 { chain += &format!(",atrim=start_sample={debt}"); }
        if debt < 0 { chain += &format!(",adelay={}S:all=1", -debt); }
        if req.gain_db.abs() > 0.01 { chain += &format!(",volume={:.2}dB", req.gain_db.clamp(-40.0, 25.0)); }
        chain += ",asetnsamples=n=1024:p=1";
        g.output("in", 0)?.input("out", 0)?.parse(&chain)?;
        g.validate()?;
        Ok(g)
    }

    fn play_once(&mut self, req: &TrackReq, hw: bool) -> Result<Outcome> {
        let mut ictx = format::input(&req.path).with_context(|| format!("open {}", req.path))?;
        let vs = ictx.streams().best(media::Type::Video).ok_or_else(|| anyhow!("no video stream"))?;
        let (vidx, vtb) = (vs.index(), vs.time_base());
        let (mut vdec, using_hw) = open_video_decoder(&vs, hw)?;
        let sar = vdec.aspect_ratio();
        let (aidx, atb, mut adec) = match ictx.streams().best(media::Type::Audio) {
            Some(s) => (Some(s.index()), s.time_base(), Some(codec::context::Context::from_parameters(s.parameters())?.decoder().audio()?)),
            None => (None, Rational(1, 1), None),
        };
        let start_s = req.start_ms as f64 / 1000.0;
        if req.start_ms > 0 {
            let ts = (req.start_ms as f64 * 1000.0) as i64;
            ictx.seek(ts, ..ts).ok();
        }
        let duration_ms = if ictx.duration() > 0 { ictx.duration() / 1000 } else { 0 };
        let debt = self.apts - (self.vpts as f64 * RATE as f64 / FPS as f64).round() as i64;
        let mut vg: Option<filter::Graph> = None;
        let mut ag = match &adec { Some(d) => Some(self.audio_graph(d, atb, req, debt)?), None => None };
        {
            let mut st = self.sh.st.lock().unwrap();
            st.current = Some(req.clone());
            st.duration_ms = duration_ms;
        }
        self.sh.emit(json!({"type": "track_started", "path": req.path, "title": req.title,
            "start_ms": req.start_ms, "duration_ms": duration_ms, "hwdec": using_hw}));
        let v0 = self.vpts;
        let (mut got_video, mut ending_sent, mut pkts) = (0u64, false, 0u64);

        for (stream, packet) in ictx.packets() {
            // control checks
            if self.sh.skip.swap(false, Ordering::SeqCst) { return Ok(Outcome::Skipped); }
            let sk = self.sh.seek_ms.swap(-1, Ordering::SeqCst);
            if sk >= 0 { return Ok(Outcome::Seek(sk)); }
            pkts += 1;
            if using_hw && got_video == 0 && pkts > 400 { return Ok(Outcome::HwFail); }
            let pos_ms = req.start_ms + ((self.vpts - v0) * 1000 / FPS as i64);
            self.sh.pos_ms.store(pos_ms, Ordering::Relaxed);
            if !ending_sent && duration_ms > 0 && duration_ms - pos_ms <= self.cfg.ending_ms {
                ending_sent = true;
                self.sh.emit(json!({"type": "track_ending", "path": req.path, "remaining_ms": duration_ms - pos_ms}));
            }

            if stream.index() == vidx {
                if vdec.send_packet(&packet).is_err() && using_hw && got_video == 0 { return Ok(Outcome::HwFail); }
                let mut f = frame::Video::empty();
                while vdec.receive_frame(&mut f).is_ok() {
                    got_video += 1;
                    let ts = f.timestamp();
                    if ts.map(|t| t as f64 * f64::from(vtb) < start_s - 0.001).unwrap_or(false) { continue; }
                    f.set_pts(ts);
                    if vg.is_none() { vg = Some(self.video_graph(&f, sar, vtb, req)?); }
                    let g = vg.as_mut().unwrap();
                    g.get("in").unwrap().source().add(&f)?;
                    self.pull_video(g)?;
                }
            } else if Some(stream.index()) == aidx {
                let (d, g) = (adec.as_mut().unwrap(), ag.as_mut().unwrap());
                d.send_packet(&packet).ok();
                let mut f = frame::Audio::empty();
                while d.receive_frame(&mut f).is_ok() {
                    let ts = f.timestamp();
                    if ts.map(|t| t as f64 * f64::from(atb) < start_s - 0.001).unwrap_or(false) { continue; }
                    f.set_pts(ts);
                    g.get("in").unwrap().source().add(&f)?;
                    self.pull_audio(g)?;
                }
            }
        }
        if using_hw && got_video == 0 { return Ok(Outcome::HwFail); }
        // flush this track's decoders/graphs (encoders and the muxer keep running)
        vdec.send_eof().ok();
        let mut f = frame::Video::empty();
        while vdec.receive_frame(&mut f).is_ok() {
            let ts = f.timestamp();
            if ts.map(|t| t as f64 * f64::from(vtb) < start_s - 0.001).unwrap_or(false) { continue; }
            f.set_pts(ts);
            if vg.is_none() { vg = Some(self.video_graph(&f, sar, vtb, req)?); }
            let g = vg.as_mut().unwrap();
            g.get("in").unwrap().source().add(&f)?;
            self.pull_video(g)?;
        }
        if let Some(g) = vg.as_mut() {
            g.get("in").unwrap().source().flush()?;
            self.pull_video(g)?;
        }
        if let (Some(d), Some(g)) = (adec.as_mut(), ag.as_mut()) {
            d.send_eof().ok();
            let mut f = frame::Audio::empty();
            while d.receive_frame(&mut f).is_ok() {
                let ts = f.timestamp();
                f.set_pts(ts);
                g.get("in").unwrap().source().add(&f)?;
                self.pull_audio(g)?;
            }
            g.get("in").unwrap().source().flush()?;
            self.pull_audio(g)?;
        }
        Ok(Outcome::Eof)
    }
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
