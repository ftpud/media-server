//! mediad: persistent ffmpeg-based player/encoder daemon.
//!
//! usage: mediad --out <rtmp-url|file> [--encoder x264|qsv] [--port 8095] [--hwdec on|off]
//!               [--play file ...]
//! env:   MEDIAD_W/MEDIAD_H (default 1920x1080), MEDIAD_FONT, MEDIAD_LIVE_FILE, MEDIAD_ENDING_MS
//!
//! Tracks are queued through the local HTTP API (see api.rs); with nothing queued it emits
//! black + silence so the stream never stops. Use `--play` for quick manual tests.
mod api;
mod engine;

use anyhow::{anyhow, Result};
use api::{Shared, TrackReq};
use engine::{Cfg, Engine};

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (mut out, mut enc, mut port, mut hwdec) = (None, "x264".to_string(), "8095".to_string(), true);
    let mut play: Vec<String> = vec![];
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--out" => { out = args.get(i + 1).cloned(); i += 2; }
            "--encoder" => { enc = args.get(i + 1).cloned().unwrap_or(enc); i += 2; }
            "--port" => { port = args.get(i + 1).cloned().unwrap_or(port); i += 2; }
            "--hwdec" => { hwdec = args.get(i + 1).map(|v| v != "off").unwrap_or(true); i += 2; }
            "--play" => { i += 1; while i < args.len() && !args[i].starts_with("--") { play.push(args[i].clone()); i += 1; } }
            other => return Err(anyhow!("unknown argument {other}")),
        }
    }
    let out = out.ok_or_else(|| anyhow!("--out required"))?;
    ffmpeg_next::init()?;
    let env_u32 = |k: &str, d: u32| std::env::var(k).ok().and_then(|v| v.parse().ok()).unwrap_or(d);
    let cfg = Cfg {
        url: out,
        qsv: enc == "qsv",
        hwdec: hwdec && enc == "qsv",
        w: env_u32("MEDIAD_W", 1920),
        h: env_u32("MEDIAD_H", 1080),
        font: std::env::var("MEDIAD_FONT").unwrap_or_else(|_| "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc".into()),
        live_file: std::env::var("MEDIAD_LIVE_FILE").ok(),
        ending_ms: env_u32("MEDIAD_ENDING_MS", 15_000) as i64,
    };
    let sh = Shared::new();
    for p in play {
        let title = std::path::Path::new(&p).file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
        sh.st.lock().unwrap().queue.push_back(TrackReq { path: p, title, ..Default::default() });
    }
    api::serve(sh.clone(), &format!("127.0.0.1:{port}"))?;
    let mut engine = Engine::new(cfg, sh)?;
    engine.run()
}
