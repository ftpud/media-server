//! Stream overlay renderer.
//!
//! Draws a 3D "now playing" card that flips in when a track starts, turns around to show
//! "up next" near the end of a track (and once a minute), and streams the panel as raw RGBA
//! frames to mediad's overlay input (MEDIAD_OVERLAY on the mediad side).
//!
//! usage: overlay [--target 127.0.0.1:8096] [--mediad 127.0.0.1:8095] [--media 127.0.0.1:8084]
//!                [--size 600x220] [--font /usr/share/fonts/truetype/wqy/wqy-microhei.ttc]
mod canvas;

use anyhow::{anyhow, Result};
use canvas::{Canvas, Rgba, TextRenderer};
use serde_json::Value;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

thread_local!(static START: Instant = Instant::now());
const FPS_ANIM: u64 = 30;
const FPS_IDLE: u64 = 2;
const FLIP_MS: f64 = 700.0;

struct Args {
    target: String,
    mediad: String,
    media: String,
    w: usize,
    h: usize,
    font: String,
}

fn args() -> Args {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let get = |k: &str, d: &str| a.iter().position(|x| x == k).and_then(|i| a.get(i + 1)).cloned().unwrap_or_else(|| d.to_string());
    let size = get("--size", "600x220");
    let (w, h) = size.split_once('x').map(|(w, h)| (w.parse().unwrap_or(600), h.parse().unwrap_or(220))).unwrap_or((600, 220));
    Args {
        target: get("--target", "127.0.0.1:8096"),
        mediad: get("--mediad", "127.0.0.1:8095"),
        media: get("--media", "127.0.0.1:8084"),
        w,
        h,
        font: get("--font", "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"),
    }
}

/// Minimal HTTP/1.0 GET returning parsed JSON (local services only).
fn get_json(host: &str, path: &str) -> Result<Value> {
    let mut s = TcpStream::connect(host)?;
    s.set_read_timeout(Some(Duration::from_secs(3)))?;
    write!(s, "GET {path} HTTP/1.0\r\nHost: {host}\r\nConnection: close\r\n\r\n")?;
    let mut buf = Vec::new();
    s.read_to_end(&mut buf)?;
    let text = String::from_utf8_lossy(&buf);
    let body = text.split_once("\r\n\r\n").map(|(_, b)| b).ok_or_else(|| anyhow!("bad response"))?;
    Ok(serde_json::from_str(body)?)
}

/// "≫　UtaGe／夢鯨" -> ("UtaGe", "夢鯨")
fn split_title(t: &str) -> (String, String) {
    let t = t.trim_start_matches('≫').trim_start_matches('\u{3000}').trim();
    match t.split_once('／') {
        Some((a, s)) => (a.trim().to_string(), s.trim().to_string()),
        None => (String::new(), t.to_string()),
    }
}

fn fmt_time(ms: i64) -> String {
    let s = (ms.max(0) / 1000) as i64;
    if s >= 3600 { format!("{}:{:02}:{:02}", s / 3600, s / 60 % 60, s % 60) } else { format!("{}:{:02}", s / 60, s % 60) }
}

fn ease_out_back(t: f64) -> f64 {
    let (c1, c3) = (1.70158, 2.70158);
    1.0 + c3 * (t - 1.0).powi(3) + c1 * (t - 1.0).powi(2)
}

#[derive(Default, Clone)]
struct Now {
    path: String,
    title: String,
    pos: i64,
    dur: i64,
    live: bool,
}

#[derive(Clone, Copy, PartialEq)]
enum Face { Front, Back }

/// Card animation: angle in degrees (0 = front facing the viewer, 180 = back facing).
struct Anim {
    from: f64,
    to: f64,
    start: Instant,
}

impl Anim {
    fn angle(&self) -> f64 {
        let t = (self.start.elapsed().as_secs_f64() * 1000.0 / FLIP_MS).clamp(0.0, 1.0);
        self.from + (self.to - self.from) * ease_out_back(t)
    }
    fn done(&self) -> bool { self.start.elapsed().as_secs_f64() * 1000.0 >= FLIP_MS }
}

const BG: Rgba = Rgba(14, 17, 24, 205);
const ACCENT: Rgba = Rgba(165, 139, 255, 255);
const PINK: Rgba = Rgba(255, 92, 168, 255);
const WHITE: Rgba = Rgba(240, 244, 250, 255);
const MUTED: Rgba = Rgba(160, 170, 188, 255);

fn draw_front(c: &mut Canvas, tr: &mut TextRenderer, now: &Now) {
    let (w, h) = (c.w as f32, c.h as f32);
    c.clear();
    c.round_rect(0.0, 0.0, w, h, 18.0, BG);
    c.gradient_rect(0.0, 0.0, 7.0, h, ACCENT, PINK);
    tr.text(c, if now.live { "NOW PLAYING" } else { "STAND BY" }, 26.0, 16.0, 15.0, ACCENT, w - 40.0);
    let (artist, song) = split_title(&now.title);
    let mut y = 40.0;
    if !artist.is_empty() {
        tr.text(c, &artist, 26.0, y, 20.0, MUTED, w - 46.0);
        y += 26.0;
    }
    tr.text(c, &song, 26.0, y, 32.0, WHITE, w - 46.0);
    // progress
    let bar_y = h - 30.0;
    c.round_rect(26.0, bar_y, w - 52.0, 6.0, 3.0, Rgba(255, 255, 255, 40));
    if now.dur > 0 {
        let p = (now.pos as f32 / now.dur as f32).clamp(0.0, 1.0);
        c.gradient_rect(26.0, bar_y, (w - 52.0) * p, 6.0, ACCENT, PINK);
    }
    let t = format!("{} / {}", fmt_time(now.pos), fmt_time(now.dur));
    let tw = tr.width(&t, 14.0);
    tr.text(c, &t, w - 26.0 - tw, bar_y - 22.0, 14.0, MUTED, w);
}

fn draw_back(c: &mut Canvas, tr: &mut TextRenderer, upcoming: &[String]) {
    let (w, h) = (c.w as f32, c.h as f32);
    c.clear();
    c.round_rect(0.0, 0.0, w, h, 18.0, BG);
    c.gradient_rect(w - 7.0, 0.0, 7.0, h, PINK, ACCENT);
    tr.text(c, "UP NEXT", 26.0, 16.0, 15.0, PINK, w - 40.0);
    let mut y = 44.0;
    if upcoming.is_empty() {
        tr.text(c, "—", 26.0, y, 22.0, MUTED, w);
    }
    for (i, t) in upcoming.iter().take(3).enumerate() {
        let (artist, song) = split_title(t);
        let label = if artist.is_empty() { song } else { format!("{song}  ·  {artist}") };
        tr.text(c, &format!("{}", i + 1), 26.0, y, 22.0, ACCENT, 40.0);
        tr.text(c, &label, 56.0, y, 22.0, if i == 0 { WHITE } else { MUTED }, w - 82.0);
        y += 34.0;
    }
}

/// Latest data from the services, refreshed by a background thread so that slow HTTP calls
/// never stall the animation.
#[derive(Default)]
struct Feed {
    status: Option<Value>,
    upcoming: Option<Vec<String>>,
}

fn spawn_poller(mediad: String, media: String) -> (Arc<Mutex<Feed>>, Arc<std::sync::atomic::AtomicBool>) {
    let feed = Arc::new(Mutex::new(Feed::default()));
    let refresh = Arc::new(std::sync::atomic::AtomicBool::new(true));
    let (f2, r2) = (feed.clone(), refresh.clone());
    std::thread::spawn(move || {
        let mut last_up = Instant::now() - Duration::from_secs(60);
        loop {
            if let Ok(s) = get_json(&mediad, "/status") { f2.lock().unwrap().status = Some(s); }
            if r2.swap(false, std::sync::atomic::Ordering::SeqCst) || last_up.elapsed() >= Duration::from_secs(10) {
                last_up = Instant::now();
                if let Ok(Value::Array(items)) = get_json(&media, "/media/list") {
                    let up = items.iter().skip(1).filter_map(|i| i["title"].as_str().map(String::from)).take(3).collect();
                    f2.lock().unwrap().upcoming = Some(up);
                }
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    });
    (feed, refresh)
}

fn main() -> Result<()> {
    let a = args();
    let (feed, refresh) = spawn_poller(a.mediad.clone(), a.media.clone());
    let font = std::fs::read(&a.font)?;
    let mut tr = TextRenderer::new(&font)?;
    let (cw, ch) = (a.w - 40, a.h - 40); // card size; margin for the 3D perspective
    let mut front = Canvas::new(cw, ch);
    let mut back = Canvas::new(cw, ch);
    let mut out = Canvas::new(a.w, a.h);

    let mut conn: Option<TcpStream> = None;
    let mut last_try = Instant::now() - Duration::from_secs(10);
    let mut now = Now::default();
    let mut upcoming: Vec<String> = vec![];
    let mut face = Face::Front;
    let mut anim: Option<Anim> = None;
    let mut angle = 90.0; // start edge-on, flip in once something plays
    let mut back_until: Option<Instant> = None;
    let mut last_periodic = Instant::now();
    let mut dirty = true;

    loop {
        let tick = Instant::now();
        // ---- data ----
        let (status, up) = {
            let mut f = feed.lock().unwrap();
            (f.status.take(), f.upcoming.take())
        };
        {
            if let Some(s) = status {
                let c = &s["current"];
                let path = c["path"].as_str().unwrap_or("").to_string();
                let changed = path != now.path;
                now = Now {
                    path,
                    title: c["title"].as_str().unwrap_or("").to_string(),
                    pos: c["position_ms"].as_i64().unwrap_or(0),
                    dur: c["duration_ms"].as_i64().unwrap_or(0),
                    live: !c.is_null(),
                };
                if changed {
                    // new track: flip the card in from edge-on
                    face = Face::Front;
                    anim = Some(Anim { from: if now.live { -90.0 } else { 0.0 }, to: 0.0, start: Instant::now() });
                    back_until = None;
                    refresh.store(true, std::sync::atomic::Ordering::SeqCst); // the queue moved on
                }
                dirty = true;
            }
        }
        if let Some(u) = up {
            upcoming = u;
            dirty = true;
        }
        // ---- choreography: show "up next" near the end and once a minute ----
        let remaining = if now.dur > 0 { now.dur - now.pos } else { i64::MAX };
        if anim.is_none() && now.live {
            let want_back = (remaining < 25_000 && remaining > 1_500) || back_until.map(|t| Instant::now() < t).unwrap_or(false);
            if face == Face::Front && want_back && !upcoming.is_empty() {
                face = Face::Back;
                anim = Some(Anim { from: 0.0, to: 180.0, start: Instant::now() });
            } else if face == Face::Back && !want_back {
                face = Face::Front;
                anim = Some(Anim { from: 180.0, to: 360.0, start: Instant::now() });
            } else if face == Face::Front && last_periodic.elapsed() >= Duration::from_secs(60) && remaining > 40_000 {
                last_periodic = Instant::now();
                back_until = Some(Instant::now() + Duration::from_secs(8));
            }
        }
        if let Some(an) = &anim {
            angle = an.angle();
            if an.done() {
                angle = an.to % 360.0;
                anim = None;
            }
            dirty = true;
        }

        // ---- render ----
        if dirty {
            draw_front(&mut front, &mut tr, &now);
            draw_back(&mut back, &mut tr, &upcoming);
            out.clear();
            let a_norm = ((angle % 360.0) + 360.0) % 360.0;
            let showing_back = a_norm > 90.0 && a_norm < 270.0;
            let card = if showing_back { &back } else { &front };
            // the back face is mirrored by the rotation: draw it at angle-180 so text reads correctly
            let draw_angle = if showing_back { a_norm - 180.0 } else if a_norm >= 270.0 { a_norm - 360.0 } else { a_norm };
            if now.live || anim.is_some() {
                out.warp_card(card, draw_angle.to_radians() as f32, a.w as f32 / 2.0, a.h as f32 / 2.0, 900.0);
            }
            dirty = false;
        }

        // ---- send ----
        if conn.is_none() && last_try.elapsed() >= Duration::from_secs(2) {
            last_try = Instant::now();
            conn = TcpStream::connect(&a.target).ok();
            if conn.is_some() { eprintln!("connected to {}", a.target); }
        }
        if std::env::var("OVERLAY_DEBUG").is_ok() {
            eprintln!("frame t={:.3} angle={:.1} anim={} render+wait_ms={}", START.with(|s| s.elapsed().as_secs_f64()), angle, anim.is_some(), tick.elapsed().as_millis());
        }
        if let Some(c) = conn.as_mut() {
            if c.write_all(&out.px).is_err() {
                eprintln!("overlay connection lost");
                conn = None;
            }
        }
        let fps = if anim.is_some() { FPS_ANIM } else { FPS_IDLE };
        let frame = Duration::from_micros(1_000_000 / fps);
        if let Some(rest) = frame.checked_sub(tick.elapsed()) { std::thread::sleep(rest); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_stream_titles() {
        assert_eq!(split_title("≫　UtaGe／夢鯨"), ("UtaGe".into(), "夢鯨".into()));
        assert_eq!(split_title("Just a title"), (String::new(), "Just a title".into()));
    }

    #[test]
    fn formats_times() {
        assert_eq!(fmt_time(65_000), "1:05");
        assert_eq!(fmt_time(3_725_000), "1:02:05");
        assert_eq!(fmt_time(-5), "0:00");
    }

    #[test]
    fn easing_ends_exactly() {
        assert!((ease_out_back(0.0)).abs() < 1e-9);
        assert!((ease_out_back(1.0) - 1.0).abs() < 1e-9);
    }

    #[test]
    fn warp_at_zero_degrees_is_identity() {
        let mut card = Canvas::new(10, 6);
        card.round_rect(0.0, 0.0, 10.0, 6.0, 0.0, Rgba(200, 10, 10, 255));
        let mut out = Canvas::new(10, 6);
        out.warp_card(&card, 0.0, 5.0, 3.0, 900.0);
        assert_eq!(&out.px[(3 * 10 + 5) * 4..(3 * 10 + 5) * 4 + 4], &[200, 10, 10, 255]);
    }
}
