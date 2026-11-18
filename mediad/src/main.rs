//! mediad: probe stage. Opens a media file and reports the first video/audio stream,
//! to verify the Rust bindings link against the ffmpeg 7.1.5 build. The real
//! clock/decoder/encoder loop comes next (see ../TODO.md, Phase 2).
use anyhow::{Context, Result};
use ffmpeg_next as ffmpeg;

fn main() -> Result<()> {
    let path = std::env::args().nth(1).context("usage: mediad <file>")?;
    ffmpeg::init()?;
    let ictx = ffmpeg::format::input(&path).with_context(|| format!("open {path}"))?;
    if let Some(v) = ictx.streams().best(ffmpeg::media::Type::Video) {
        let p = ffmpeg::codec::context::Context::from_parameters(v.parameters())?;
        let d = p.decoder().video()?;
        println!("video: {:?} {}x{} {:?} fps={}", d.id(), d.width(), d.height(), d.format(), v.avg_frame_rate());
    }
    if let Some(a) = ictx.streams().best(ffmpeg::media::Type::Audio) {
        let p = ffmpeg::codec::context::Context::from_parameters(a.parameters())?;
        let d = p.decoder().audio()?;
        println!("audio: {:?} {} Hz {} ch", d.id(), d.rate(), d.channels());
    }
    println!("duration: {:.2}s", ictx.duration() as f64 / f64::from(ffmpeg::ffi::AV_TIME_BASE));
    Ok(())
}
