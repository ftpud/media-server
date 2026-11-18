//! mediad skeleton (Phase 2): plays a list of files back-to-back into ONE output
//! with a single continuous clock and a single persistent encoder.
//!
//! usage: mediad <out.(mp4|flv|ts)> <x264|qsv> <in1> [in2 ...]
//! env:   MEDIAD_W/MEDIAD_H (default 1280x720), MEDIAD_FONT (drawtext font file)
//!
//! Output timestamps are generated from counters (video: frames, audio: samples),
//! never copied from the inputs, so track boundaries cannot produce discontinuities.
use anyhow::{anyhow, Context, Result};
use ffmpeg_next as ffmpeg;
use ffmpeg::{codec, encoder, filter, format, frame, media, ChannelLayout, Dictionary, Rational};

const FPS: i32 = 30;
const RATE: i32 = 44100;

struct Out {
    octx: format::context::Output,
    venc: encoder::Video,
    aenc: encoder::Audio,
    v_idx: usize,
    a_idx: usize,
    v_tb: Rational,
    a_tb: Rational,
    vpts: i64,
    apts: i64,
}

impl Out {
    fn drain_video(&mut self) -> Result<()> {
        let mut pkt = ffmpeg::Packet::empty();
        while self.venc.receive_packet(&mut pkt).is_ok() {
            pkt.set_stream(self.v_idx);
            pkt.rescale_ts(Rational(1, FPS), self.v_tb);
            pkt.write_interleaved(&mut self.octx)?;
        }
        Ok(())
    }
    fn drain_audio(&mut self) -> Result<()> {
        let mut pkt = ffmpeg::Packet::empty();
        while self.aenc.receive_packet(&mut pkt).is_ok() {
            pkt.set_stream(self.a_idx);
            pkt.rescale_ts(Rational(1, RATE), self.a_tb);
            pkt.write_interleaved(&mut self.octx)?;
        }
        Ok(())
    }
    fn pull_video(&mut self, g: &mut filter::Graph) -> Result<()> {
        let mut f = frame::Video::empty();
        while g.get("out").unwrap().sink().frame(&mut f).is_ok() {
            f.set_pts(Some(self.vpts));
            self.vpts += 1;
            self.venc.send_frame(&f)?;
            self.drain_video()?;
        }
        Ok(())
    }
    fn pull_audio(&mut self, g: &mut filter::Graph) -> Result<()> {
        let mut f = frame::Audio::empty();
        while g.get("out").unwrap().sink().frame(&mut f).is_ok() {
            f.set_pts(Some(self.apts));
            self.apts += f.samples() as i64;
            self.aenc.send_frame(&f)?;
            self.drain_audio()?;
        }
        Ok(())
    }
}

fn sanitize(s: &str) -> String {
    s.chars().map(|c| if c.is_ascii_alphanumeric() || c == ' ' || c == '-' { c } else { ' ' }).collect()
}

fn video_graph(dec: &ffmpeg::decoder::Video, tb: Rational, w: u32, h: u32, nv12: bool, title: &str) -> Result<filter::Graph> {
    let mut g = filter::Graph::new();
    let pix: ffmpeg::ffi::AVPixelFormat = dec.format().into();
    let sar = dec.aspect_ratio();
    let (sn, sd) = if sar.numerator() > 0 && sar.denominator() > 0 { (sar.numerator(), sar.denominator()) } else { (1, 1) };
    let args = format!(
        "video_size={}x{}:pix_fmt={}:time_base={}/{}:pixel_aspect={}/{}",
        dec.width(), dec.height(), pix as i32, tb.numerator(), tb.denominator(), sn, sd
    );
    g.add(&filter::find("buffer").unwrap(), "in", &args)?;
    g.add(&filter::find("buffersink").unwrap(), "out", "")?;
    let font = std::env::var("MEDIAD_FONT").unwrap_or_else(|_| "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc".into());
    let draw = if std::path::Path::new(&font).exists() {
        format!(
            ",drawtext=fontfile={font}:text='{}':x=2:y=2:fontsize=24:fontcolor=white:box=1:boxcolor=black:boxborderw=4",
            sanitize(title)
        )
    } else {
        String::new()
    };
    let spec = format!(
        "scale={w}:{h}:force_original_aspect_ratio=decrease,pad={w}:{h}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps={FPS}{draw},format={}",
        if nv12 { "nv12" } else { "yuv420p" }
    );
    g.output("in", 0)?.input("out", 0)?.parse(&spec)?;
    g.validate()?;
    Ok(g)
}

fn audio_graph(dec: &ffmpeg::decoder::Audio, tb: Rational) -> Result<filter::Graph> {
    let mut g = filter::Graph::new();
    let layout = if dec.channel_layout().bits() != 0 { dec.channel_layout() } else { ChannelLayout::default(dec.channels() as i32) };
    let fmt: ffmpeg::ffi::AVSampleFormat = dec.format().into();
    let fmt_name = unsafe { std::ffi::CStr::from_ptr(ffmpeg::ffi::av_get_sample_fmt_name(fmt)) }.to_string_lossy().into_owned();
    let args = format!(
        "time_base={}/{}:sample_rate={}:sample_fmt={}:channel_layout=0x{:x}",
        tb.numerator(), tb.denominator(), dec.rate(), fmt_name, layout.bits()
    );
    g.add(&filter::find("abuffer").unwrap(), "in", &args)?;
    g.add(&filter::find("abuffersink").unwrap(), "out", "")?;
    let spec = format!("aresample={RATE},aformat=sample_fmts=fltp:channel_layouts=stereo,asetnsamples=n=1024:p=1");
    g.output("in", 0)?.input("out", 0)?.parse(&spec)?;
    g.validate()?;
    Ok(g)
}

fn open_output(path: &str, qsv: bool, w: u32, h: u32) -> Result<Out> {
    let mut octx = format::output(path)?;
    let global = octx.format().flags().contains(format::Flags::GLOBAL_HEADER);

    let vcodec = encoder::find_by_name(if qsv { "h264_qsv" } else { "libx264" }).ok_or_else(|| anyhow!("video encoder not found"))?;
    let mut v = codec::context::Context::new_with_codec(vcodec).encoder().video()?;
    v.set_width(w);
    v.set_height(h);
    v.set_format(if qsv { format::Pixel::NV12 } else { format::Pixel::YUV420P });
    v.set_time_base(Rational(1, FPS));
    v.set_frame_rate(Some(Rational(FPS, 1)));
    v.set_bit_rate(4_500_000);
    v.set_max_bit_rate(5_500_000);
    v.set_gop(60);
    if global { v.set_flags(codec::Flags::GLOBAL_HEADER); }
    let mut vopts = Dictionary::new();
    vopts.set("preset", if qsv { "medium" } else { "veryfast" });
    let venc = v.open_with(vopts)?;
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
    Ok(Out { octx, venc, aenc, v_idx, a_idx, v_tb, a_tb, vpts: 0, apts: 0 })
}

fn play_track(out: &mut Out, path: &str, qsv: bool, w: u32, h: u32) -> Result<()> {
    let title = std::path::Path::new(path).file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let mut ictx = format::input(&path).with_context(|| format!("open {path}"))?;
    let vs = ictx.streams().best(media::Type::Video).ok_or_else(|| anyhow!("no video stream"))?;
    let (vidx, vtb) = (vs.index(), vs.time_base());
    let mut vdec = codec::context::Context::from_parameters(vs.parameters())?.decoder().video()?;
    let ast = ictx.streams().best(media::Type::Audio);
    let (aidx, atb, mut adec) = match ast {
        Some(s) => (Some(s.index()), s.time_base(), Some(codec::context::Context::from_parameters(s.parameters())?.decoder().audio()?)),
        None => (None, Rational(1, 1), None),
    };
    let mut vg = video_graph(&vdec, vtb, w, h, qsv, &title)?;
    let mut ag = match &adec { Some(d) => Some(audio_graph(d, atb)?), None => None };
    let (v0, a0) = (out.vpts, out.apts);

    for (stream, packet) in ictx.packets() {
        if stream.index() == vidx {
            vdec.send_packet(&packet)?;
            let mut f = frame::Video::empty();
            while vdec.receive_frame(&mut f).is_ok() {
                let ts = f.timestamp();
                f.set_pts(ts);
                vg.get("in").unwrap().source().add(&f)?;
                out.pull_video(&mut vg)?;
            }
        } else if Some(stream.index()) == aidx {
            let (d, g) = (adec.as_mut().unwrap(), ag.as_mut().unwrap());
            d.send_packet(&packet)?;
            let mut f = frame::Audio::empty();
            while d.receive_frame(&mut f).is_ok() {
                let ts = f.timestamp();
                f.set_pts(ts);
                g.get("in").unwrap().source().add(&f)?;
                out.pull_audio(g)?;
            }
        }
    }
    // flush this track's decoders and filter graphs (the encoders keep running)
    vdec.send_eof()?;
    let mut f = frame::Video::empty();
    while vdec.receive_frame(&mut f).is_ok() {
        let ts = f.timestamp();
        f.set_pts(ts);
        vg.get("in").unwrap().source().add(&f)?;
        out.pull_video(&mut vg)?;
    }
    vg.get("in").unwrap().source().flush()?;
    out.pull_video(&mut vg)?;
    if let (Some(d), Some(g)) = (adec.as_mut(), ag.as_mut()) {
        d.send_eof()?;
        let mut f = frame::Audio::empty();
        while d.receive_frame(&mut f).is_ok() {
            let ts = f.timestamp();
            f.set_pts(ts);
            g.get("in").unwrap().source().add(&f)?;
            out.pull_audio(g)?;
        }
        g.get("in").unwrap().source().flush()?;
        out.pull_audio(g)?;
    }
    let (vs_s, as_s) = ((out.vpts - v0) as f64 / FPS as f64, (out.apts - a0) as f64 / RATE as f64);
    eprintln!("track done: {title}: video {vs_s:.3}s audio {as_s:.3}s drift {:+.3}s", as_s - vs_s);
    Ok(())
}

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        return Err(anyhow!("usage: mediad <out> <x264|qsv> <in1> [in2 ...]"));
    }
    ffmpeg::init()?;
    let (w, h) = (
        std::env::var("MEDIAD_W").ok().and_then(|v| v.parse().ok()).unwrap_or(1280u32),
        std::env::var("MEDIAD_H").ok().and_then(|v| v.parse().ok()).unwrap_or(720u32),
    );
    let qsv = args[2] == "qsv";
    let mut out = open_output(&args[1], qsv, w, h)?;
    for path in &args[3..] {
        // a bad track is skipped, it must never stop the stream
        if let Err(e) = play_track(&mut out, path, qsv, w, h) {
            eprintln!("track failed, skipping: {path}: {e:#}");
        }
    }
    out.venc.send_eof()?;
    out.drain_video()?;
    out.aenc.send_eof()?;
    out.drain_audio()?;
    out.octx.write_trailer()?;
    eprintln!("done: {:.2}s video, {:.2}s audio", out.vpts as f64 / FPS as f64, out.apts as f64 / RATE as f64);
    Ok(())
}
