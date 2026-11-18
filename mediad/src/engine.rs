//! The real-time output engine.
//!
//! One clock, one encoder pair, one muxer; tracks come and go, the output never restarts.
//! Every tick emits exactly one video frame and just enough audio to keep audio == video.
//! Tracks decode on their own threads (`source::Handle`), so the clock never waits on I/O:
//! if a track is late, the last frame is repeated with silence (counted as `late_frames`).
//!
//! Output path: [crossfade A/B] -> post graph (zmq, live text) -> overlay -> encoder -> muxer
use crate::api::{Shared, TrackReq};
use crate::overlay::{Latest, OverlayCfg};
use crate::source::{esc_path, Handle, Pull, SrcCfg, FPS, NB, RATE};
use anyhow::{anyhow, Result};
use ffmpeg::{codec, encoder, filter, format, frame, ChannelLayout, Dictionary, Rational};
use ffmpeg_next as ffmpeg;
use serde_json::json;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};

const LEAD: Duration = Duration::from_millis(200);
const PREROLL_MS: i64 = 10_000;

pub struct Cfg {
    pub url: String,
    pub src: SrcCfg,
    pub live_file: Option<String>,
    pub ending_ms: i64,
    pub xfade_ms: i64,
    pub zmq: Option<String>,
    pub overlay: Option<OverlayCfg>,
}

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

fn writable_v(f: &mut frame::Video) { unsafe { ffmpeg::ffi::av_frame_make_writable(f.as_mut_ptr()); } }
fn writable_a(f: &mut frame::Audio) { unsafe { ffmpeg::ffi::av_frame_make_writable(f.as_mut_ptr()); } }

fn planes(nv12: bool, w: usize, h: usize) -> Vec<(usize, usize)> {
    if nv12 { vec![(w, h), (w, h / 2)] } else { vec![(w, h), (w / 2, h / 2), (w / 2, h / 2)] }
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
    cur: Option<Handle>,
    next: Option<Handle>,
    xfade_from: Option<(i64, i64)>, // (first output frame of the transition, length in frames)
    filler_v: Option<filter::Graph>,
    filler_a: Option<filter::Graph>,
    post: Option<filter::Graph>,
    overlay: Option<Latest>,
    last: Option<frame::Video>,
    ending_sent: bool,
    idle: bool,
    late_frames: i64,
}

impl Engine {
    pub fn new(cfg: Cfg, sh: Arc<Shared>) -> Result<Engine> {
        let qsv = cfg.src.qsv;
        let mut octx = open_muxer(&cfg.url)?;
        let global = octx.format().flags().contains(format::Flags::GLOBAL_HEADER);
        let vcodec = encoder::find_by_name(if qsv { "h264_qsv" } else { "libx264" }).ok_or_else(|| anyhow!("video encoder not found"))?;
        let mut v = codec::context::Context::new_with_codec(vcodec).encoder().video()?;
        v.set_width(cfg.src.w);
        v.set_height(cfg.src.h);
        v.set_format(if qsv { format::Pixel::NV12 } else { format::Pixel::YUV420P });
        v.set_time_base(Rational(1, FPS));
        v.set_frame_rate(Some(Rational(FPS, 1)));
        v.set_bit_rate(4_500_000);
        v.set_max_bit_rate(5_500_000);
        v.set_gop(60);
        if global { v.set_flags(codec::Flags::GLOBAL_HEADER); }
        let mut vo = Dictionary::new();
        vo.set("preset", if qsv { "medium" } else { "veryfast" });
        if qsv {
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
            filler_v: None, filler_a: None, post: None, overlay, last: None, ending_sent: false, idle: false, late_frames: 0,
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
        let qsv = self.cfg.src.qsv;
        let r = (|| -> Result<()> {
            let mut o = open_muxer(&self.cfg.url)?;
            let mut vs = o.add_stream(encoder::find_by_name(if qsv { "h264_qsv" } else { "libx264" }))?;
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

    fn due(&self) -> Instant { self.t0 + Duration::from_secs_f64(self.vpts as f64 / FPS as f64) }

    /// Real-time pacing. If we fell far behind (e.g. a slow reconnect), re-anchor instead of bursting.
    fn pace(&mut self) {
        let target = self.due();
        let now = Instant::now();
        if now + LEAD < target {
            std::thread::sleep(target - now - LEAD);
        } else if now > target + Duration::from_secs(2) {
            self.t0 += now - target;
        }
    }

    fn post_graph(&self) -> Result<Option<filter::Graph>> {
        let font = &self.cfg.src.font;
        let have_font = std::path::Path::new(font).exists();
        let mut parts = vec![];
        if let Some(z) = &self.cfg.zmq { parts.push(format!("zmq=bind_address='{}'", z.replace(':', "\\:"))); }
        if let Some(lf) = &self.cfg.live_file {
            // drawtext refuses to start on a missing textfile; the live service creates it later
            if !std::path::Path::new(lf).exists() { let _ = std::fs::write(lf, " "); }
        }
        if let (Some(lf), true) = (&self.cfg.live_file, have_font) {
            parts.push(format!("drawtext@live=fontfile={font}:textfile='{}':reload=1:x=2:y=H-th:fontsize=24:fontcolor=white:box=1:boxcolor=black@0.4:boxborderw=4",
                esc_path(lf)));
        } else if self.cfg.zmq.is_some() && have_font {
            // a named text slot that can be driven over zmq
            parts.push(format!("drawtext@live=fontfile={font}:text=' ':x=2:y=H-th:fontsize=24:fontcolor=white:box=1:boxcolor=black@0.4:boxborderw=4"));
        }
        if parts.is_empty() { return Ok(None); }
        let mut g = filter::Graph::new();
        let pix = if self.cfg.src.qsv { ffmpeg::ffi::AVPixelFormat::AV_PIX_FMT_NV12 } else { ffmpeg::ffi::AVPixelFormat::AV_PIX_FMT_YUV420P };
        let args = format!("video_size={}x{}:pix_fmt={}:time_base=1/{FPS}:pixel_aspect=1/1", self.cfg.src.w, self.cfg.src.h, pix as i32);
        g.add(&filter::find("buffer").unwrap(), "in", &args)?;
        g.add(&filter::find("buffersink").unwrap(), "out", "")?;
        let fmt = if self.cfg.src.qsv { "nv12" } else { "yuv420p" };
        g.output("in", 0)?.input("out", 0)?.parse(&format!("{},format={fmt}", parts.join(",")))?;
        g.validate()?;
        Ok(Some(g))
    }

    fn blend_overlay(&self, f: &mut frame::Video) {
        let Some(latest) = &self.overlay else { return };
        let Some(ov) = latest.lock().unwrap().clone() else { return };
        let o = self.cfg.overlay.as_ref().unwrap();
        let (fw, fh) = (self.cfg.src.w as usize, self.cfg.src.h as usize);
        writable_v(f);
        let nv12 = self.cfg.src.qsv;
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
        self.last = Some(f.clone());
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
            let fmt = if self.cfg.src.qsv { "nv12" } else { "yuv420p" };
            g.parse(&format!("color=c=black:s={}x{}:r={FPS},format={fmt},buffersink@out", self.cfg.src.w, self.cfg.src.h))?;
            g.validate()?;
            self.filler_v = Some(g);
        }
        let g = self.filler_v.as_mut().unwrap();
        let mut v = frame::Video::empty();
        g.get("out").or_else(|| g.get("buffersink@out")).ok_or_else(|| anyhow!("filler video sink"))?.sink().frame(&mut v)?;
        Ok(v)
    }

    /// Audio for the frame just emitted: keep apts within one audio frame of vpts.
    /// `from_source=false` (late/idle tick) emits silence and leaves the sources' audio untouched,
    /// so their audio stays aligned with their video.
    fn audio_for_tick(&mut self, from_source: bool, mix: Option<f32>) -> Result<()> {
        while (self.apts as f64) < self.vpts as f64 * RATE as f64 / FPS as f64 {
            let a = if from_source { self.cur.as_mut().and_then(|s| s.audio()) } else { None };
            let mut a = match a { Some(a) => a, None => self.silence()? };
            if let (true, Some(t)) = (from_source, mix) {
                let b = self.next.as_mut().and_then(|s| s.audio());
                writable_a(&mut a);
                if let Some(b) = b {
                    let n = a.samples().min(b.samples());
                    for ch in 0..2 {
                        let bp: Vec<f32> = b.plane::<f32>(ch)[..n].to_vec();
                        let ap = a.plane_mut::<f32>(ch);
                        for i in 0..n { ap[i] = ap[i] * (1.0 - t) + bp[i] * t; }
                    }
                } else {
                    // next track has no audio (yet): fade the current one out
                    for ch in 0..2 { for s in a.plane_mut::<f32>(ch).iter_mut() { *s *= 1.0 - t; } }
                }
            }
            self.emit_audio(a)?;
        }
        let (v_ms, a_ms) = (self.vpts * 1000 / FPS as i64, self.apts * 1000 / RATE as i64);
        self.sh.out_ms.store(v_ms, Ordering::Relaxed);
        self.sh.av_ms.store(a_ms - v_ms, Ordering::Relaxed);
        self.sh.late_frames.store(self.late_frames, Ordering::Relaxed);
        Ok(())
    }

    // ---------- track bookkeeping ----------
    fn announce(&mut self, h: &mut Handle) {
        if h.announced { return; }
        h.announced = true;
        let info = h.info();
        let dur = info.as_ref().map(|i| i.duration_ms).unwrap_or(0);
        {
            let mut st = self.sh.st.lock().unwrap();
            st.current = Some(h.req.clone());
            st.duration_ms = dur;
            st.filler = false;
        }
        self.idle = false;
        self.sh.emit(json!({"type": "track_started", "path": h.req.path, "title": h.req.title,
            "start_ms": h.req.start_ms, "duration_ms": dur, "hwdec": info.as_ref().map(|i| i.using_hw).unwrap_or(false)}));
    }

    fn ended(&self, req: &TrackReq, reason: &str) {
        self.sh.emit(json!({"type": "track_ended", "path": req.path, "reason": reason}));
    }

    fn failed(&self, h: &Handle, msg: &str) {
        self.sh.emit(json!({"type": "track_error", "path": h.req.path, "message": msg}));
        self.ended(&h.req, "error");
    }

    fn spawn(&self, req: TrackReq) -> Handle { Handle::spawn(req, self.cfg.src.clone()) }

    /// Make the next track current: a pre-rolled one if available, else the queue head.
    fn promote_next(&mut self) {
        self.xfade_from = None;
        self.ending_sent = false;
        self.cur = self.next.take();
        if self.cur.is_none() {
            if let Some(req) = self.sh.pop_track() { self.cur = Some(self.spawn(req)); }
        }
    }

    // ---------- main loop ----------
    pub fn run(&mut self) -> Result<()> {
        loop {
            // --- control ---
            if self.sh.clear_next.swap(false, Ordering::SeqCst) {
                self.next = None; // /clear: a "now" load follows; drop any pre-rolled track
                self.xfade_from = None;
            }
            if self.sh.skip.swap(false, Ordering::SeqCst) {
                if let Some(h) = self.cur.take() { self.ended(&h.req, "skipped"); }
                self.promote_next();
            }
            let sk = self.sh.seek_ms.swap(-1, Ordering::SeqCst);
            if sk >= 0 {
                if let Some(h) = self.cur.take() {
                    let mut req = h.req.clone();
                    req.start_ms = sk;
                    self.cur = Some(self.spawn(req));
                    self.xfade_from = None;
                    self.ending_sent = false;
                }
            }
            if self.cur.is_none() { self.promote_next(); }

            // --- nothing to play: black + silence ---
            if self.cur.is_none() {
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
                self.audio_for_tick(false, None)?;
                continue;
            }

            // --- schedule: ending event, pre-roll, transition ---
            let (remaining, pos) = { let c = self.cur.as_ref().unwrap(); (c.remaining_ms(), c.position_ms()) };
            self.sh.pos_ms.store(pos, Ordering::Relaxed);
            if let Some(rem) = remaining {
                if !self.ending_sent && rem <= self.cfg.ending_ms {
                    self.ending_sent = true;
                    let p = self.cur.as_ref().unwrap().req.path.clone();
                    self.sh.emit(json!({"type": "track_ending", "path": p, "remaining_ms": rem}));
                }
                if self.next.is_none() && rem <= PREROLL_MS.max(self.cfg.xfade_ms + 3000) {
                    if let Some(req) = self.sh.pop_track() { self.next = Some(self.spawn(req)); }
                }
                let next_ok = self.next.as_ref().map(|n| n.info().is_some()).unwrap_or(false);
                if self.xfade_from.is_none() && next_ok && self.cfg.xfade_ms > 0 && rem <= self.cfg.xfade_ms {
                    let len = (rem * FPS as i64 / 1000).max(1);
                    self.xfade_from = Some((self.vpts, len));
                }
            }
            if self.next.as_ref().map(|n| n.error().is_some()).unwrap_or(false) {
                let n = self.next.take().unwrap();
                let msg = n.error().unwrap_or_default();
                self.failed(&n, &msg);
                self.xfade_from = None;
            }

            // --- video for this tick (wait at most until the frame is due) ---
            let deadline = self.due() + Duration::from_millis(40);
            let pulled = self.cur.as_mut().unwrap().video(deadline);
            match pulled {
                Pull::Eof => {
                    let h = self.cur.take().unwrap();
                    match h.error() {
                        Some(msg) => self.failed(&h, &msg),
                        None => self.ended(&h.req, "eof"),
                    }
                    if self.xfade_from.is_some() {
                        // the transition already brought the next track in: just make it current
                        self.cur = self.next.take();
                        self.xfade_from = None;
                        self.ending_sent = false;
                    } else {
                        self.promote_next();
                    }
                    continue;
                }
                Pull::Late => {
                    // the track is not ready (opening, seeking, slow disk, CPU spike): hold the picture
                    self.late_frames += 1;
                    let v = match self.last.clone() { Some(v) => v, None => self.black()? };
                    self.emit_video(v)?;
                    self.audio_for_tick(false, None)?;
                }
                Pull::Frame(mut a) => {
                    let mut h = self.cur.take().unwrap();
                    self.announce(&mut h);
                    self.cur = Some(h);
                    let mut mix = None;
                    if let Some((from, len)) = self.xfade_from {
                        let t = (((self.vpts - from) as f32 + 1.0) / (len as f32 + 1.0)).clamp(0.0, 1.0);
                        let mut n = self.next.take().unwrap();
                        if let Pull::Frame(b) = n.video(Instant::now() + Duration::from_millis(10)) {
                            self.announce(&mut n); // the next track is now visible/audible
                            blend(&mut a, &b, t, self.cfg.src.qsv, self.cfg.src.w as usize, self.cfg.src.h as usize);
                        }
                        self.next = Some(n);
                        mix = Some(t);
                    }
                    self.emit_video(a)?;
                    self.audio_for_tick(true, mix)?;
                }
            }
        }
    }
}
