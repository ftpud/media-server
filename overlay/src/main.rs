//! Stream overlay renderer (top-left corner).
//!
//! - compact "now playing" pill in the corner (mediad hides its own text title while we are connected)
//! - on every new track: a big card flips in, holds, then shrinks into the pill
//! - 30 s before the end: a "NEXT" banner flips down under the pill with a countdown bar
//! - on a chat !list: the upcoming tracks cascade in as a panel, hold, then fold away
//!
//! Frames are raw RGBA, streamed to mediad's overlay input (MEDIAD_OVERLAY on the mediad side).
//! usage: overlay [--target 127.0.0.1:8096] [--mediad 127.0.0.1:8095] [--media 127.0.0.1:8084]
//!                [--size 1000x440] [--font /usr/share/fonts/truetype/wqy/wqy-microhei.ttc]
mod canvas;

use anyhow::{anyhow, Result};
use canvas::{Canvas, Rgba, TextRenderer};
use serde_json::Value;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

thread_local!(static DUMPED: std::cell::Cell<u32> = std::cell::Cell::new(0));

// ---------- look ----------
const BG: Rgba = Rgba(12, 14, 20, 200);
const BG_SOFT: Rgba = Rgba(12, 14, 20, 175);
const ACCENT: Rgba = Rgba(165, 139, 255, 255);
const PINK: Rgba = Rgba(255, 92, 168, 255);
const WHITE: Rgba = Rgba(244, 246, 252, 255);
const MUTED: Rgba = Rgba(170, 178, 196, 255);
const X0: f32 = 16.0; // corner margin
const Y0: f32 = 14.0;
const FOCAL: f32 = 800.0;

// ---------- timeline (seconds) ----------
const BIG_IN: f64 = 0.6; // flip in
const BIG_HOLD: f64 = 5.0; // big card visible until
const MORPH: f64 = 0.6; // big -> pill
const NEXT_AT_MS: i64 = 30_000; // banner this long before the end
const NEXT_IN: f64 = 0.55;
const LIST_ROWS: usize = 8;
const LIST_STAGGER: f64 = 0.07;
const LIST_IN: f64 = 0.45;
const LIST_HOLD: f64 = 12.0;
const LIST_OUT: f64 = 0.4;

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
    let size = get("--size", "1000x440");
    let (w, h) = size.split_once('x').map(|(w, h)| (w.parse().unwrap_or(1000), h.parse().unwrap_or(440))).unwrap_or((1000, 440));
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

#[allow(dead_code)]
fn fmt_time(ms: i64) -> String {
    let s = ms.max(0) / 1000;
    if s >= 3600 { format!("{}:{:02}:{:02}", s / 3600, s / 60 % 60, s % 60) } else { format!("{}:{:02}", s / 60, s % 60) }
}

fn ease_out_back(t: f64) -> f64 {
    let (c1, c3) = (1.70158, 2.70158);
    1.0 + c3 * (t - 1.0).powi(3) + c1 * (t - 1.0).powi(2)
}
fn ease_in_out(t: f64) -> f64 { let t = t.clamp(0.0, 1.0); t * t * (3.0 - 2.0 * t) }
fn prog(t: f64, start: f64, len: f64) -> f64 { ((t - start) / len).clamp(0.0, 1.0) }

// ---------- data feed (background thread: slow HTTP never stalls the animation) ----------
#[derive(Default)]
struct Feed {
    status: Option<Value>,
    upcoming: Option<Vec<String>>,
    list_at: Option<i64>,
}

fn spawn_poller(mediad: String, media: String) -> (Arc<Mutex<Feed>>, Arc<AtomicBool>) {
    let feed = Arc::new(Mutex::new(Feed::default()));
    let refresh = Arc::new(AtomicBool::new(true));
    let (f2, r2) = (feed.clone(), refresh.clone());
    std::thread::spawn(move || {
        let mut last_up = Instant::now() - Duration::from_secs(60);
        loop {
            if let Ok(s) = get_json(&mediad, "/status") { f2.lock().unwrap().status = Some(s); }
            if let Ok(v) = get_json(&media, "/media/list/requested") {
                if let Some(at) = v["at"].as_i64() { f2.lock().unwrap().list_at = Some(at); }
            }
            if r2.swap(false, Ordering::SeqCst) || last_up.elapsed() >= Duration::from_secs(10) {
                last_up = Instant::now();
                if let Ok(Value::Array(items)) = get_json(&media, "/media/list?internal=1") {
                    let up = items.iter().skip(1).filter_map(|i| i["title"].as_str().map(String::from)).take(LIST_ROWS).collect();
                    f2.lock().unwrap().upcoming = Some(up);
                }
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    });
    (feed, refresh)
}

// ---------- layers ----------
struct Layers {
    big: Canvas,
    pill: Canvas,
    next: Canvas,
    rows: Vec<Canvas>,
}

/// Big "now playing" card: label, song (large), artist.
fn render_big(tr: &mut TextRenderer, artist: &str, song: &str) -> Canvas {
    let song = tr.fit(song, 46.0, 860.0);
    let artist = tr.fit(artist, 24.0, 860.0);
    let w = (tr.width(&song, 46.0).max(tr.width(&artist, 24.0)).max(170.0) + 56.0).ceil() as usize;
    let h = if artist.is_empty() { 112 } else { 146 };
    let mut c = Canvas::new(w, h);
    c.round_rect(0.0, 0.0, w as f32, h as f32, 16.0, BG);
    c.gradient_rect(0.0, 0.0, 6.0, h as f32, ACCENT, PINK);
    tr.text(&mut c, "NOW PLAYING", 26.0, 14.0, 14.0, ACCENT, w as f32);
    tr.bold(&mut c, &song, 26.0, 36.0, 46.0, WHITE, w as f32);
    if !artist.is_empty() { tr.text(&mut c, &artist, 27.0, 96.0, 24.0, MUTED, w as f32); }
    c
}

/// Compact corner pill: ♪ song · artist.
fn render_pill(tr: &mut TextRenderer, artist: &str, song: &str) -> Canvas {
    let song = tr.fit(song, 21.0, 560.0);
    let artist = tr.fit(artist, 17.0, 260.0);
    let sw = tr.width(&song, 21.0);
    let aw = if artist.is_empty() { 0.0 } else { tr.width(&artist, 17.0) + 22.0 };
    let w = (sw + aw + 62.0).ceil() as usize;
    let h = 40;
    let mut c = Canvas::new(w, h);
    c.round_rect(0.0, 0.0, w as f32, h as f32, 20.0, BG_SOFT);
    // little equalizer mark
    for (i, bh) in [10.0f32, 16.0, 12.0].iter().enumerate() {
        c.gradient_rect(16.0 + i as f32 * 6.0, 20.0 - bh / 2.0 + 1.0, 3.5, *bh, ACCENT, PINK);
    }
    tr.bold(&mut c, &song, 42.0, 8.0, 21.0, WHITE, w as f32);
    if !artist.is_empty() {
        tr.text(&mut c, "·", 42.0 + sw + 7.0, 10.0, 17.0, MUTED, w as f32);
        tr.text(&mut c, &artist, 42.0 + sw + 20.0, 11.0, 17.0, MUTED, w as f32);
    }
    c
}

/// "NEXT ▸ song · artist" banner; the countdown bar is drawn live on top.
fn render_next(tr: &mut TextRenderer, title: &str) -> Canvas {
    let (artist, song) = split_title(title);
    let label = if artist.is_empty() { song } else { format!("{song}  ·  {artist}") };
    let label = tr.fit(&label, 18.0, 560.0);
    let w = (tr.width(&label, 18.0) + 112.0).ceil() as usize;
    let h = 36;
    let mut c = Canvas::new(w, h);
    c.round_rect(0.0, 0.0, w as f32, h as f32, 12.0, BG_SOFT);
    c.round_rect(5.0, 5.0, 74.0, 26.0, 9.0, PINK);
    tr.bold(&mut c, "NEXT", 13.0, 9.0, 15.0, WHITE, 60.0);
    c.triangle_right(62.0, 12.0, 8.0, 12.0, WHITE);
    tr.text(&mut c, &label, 92.0, 8.0, 18.0, WHITE, w as f32);
    c
}

/// One row of the !list panel (index 0 = header).
fn render_row(tr: &mut TextRenderer, i: usize, title: &str) -> Canvas {
    let h = if i == 0 { 34 } else { 38 };
    if i == 0 {
        let mut c = Canvas::new(170, h);
        c.round_rect(0.0, 0.0, 170.0, h as f32, 12.0, BG);
        c.gradient_rect(0.0, h as f32 - 3.0, 170.0, 3.0, ACCENT, PINK);
        tr.bold(&mut c, "UP NEXT", 16.0, 8.0, 17.0, WHITE, 160.0);
        return c;
    }
    let (artist, song) = split_title(title);
    let song = tr.fit(&song, 19.0, 440.0);
    let artist = tr.fit(&artist, 16.0, 220.0);
    let sw = tr.width(&song, 19.0);
    let aw = if artist.is_empty() { 0.0 } else { tr.width(&artist, 16.0) + 14.0 };
    let w = (44.0 + sw + aw + 18.0).max(240.0).ceil() as usize;
    let mut c = Canvas::new(w, h);
    c.round_rect(0.0, 0.0, w as f32, h as f32 - 4.0, 10.0, BG_SOFT);
    tr.bold(&mut c, &format!("{i}"), 14.0, 7.0, 19.0, if i == 1 { PINK } else { ACCENT }, 30.0);
    tr.text(&mut c, &song, 44.0, 7.0, 19.0, WHITE, w as f32);
    if !artist.is_empty() { tr.text(&mut c, &artist, 44.0 + sw + 14.0, 9.0, 16.0, MUTED, w as f32); }
    c
}

fn main() -> Result<()> {
    let a = args();
    let font = std::fs::read(&a.font)?;
    let mut tr = TextRenderer::new(&font)?;
    let (feed, refresh) = spawn_poller(a.mediad.clone(), a.media.clone());
    let mut out = Canvas::new(a.w, a.h);
    let mut layers = Layers { big: Canvas::new(1, 1), pill: Canvas::new(1, 1), next: Canvas::new(1, 1), rows: vec![] };

    let mut conn: Option<TcpStream> = None;
    let mut last_try = Instant::now() - Duration::from_secs(10);
    let (mut path, mut pos, mut dur, mut live) = (String::new(), 0i64, 0i64, false);
    let mut upcoming: Vec<String> = vec![];
    let mut track_t0 = Instant::now() - Duration::from_secs(3600); // when the current track appeared
    let mut next_t0: Option<Instant> = None; // when the NEXT banner appeared
    let mut next_for = String::new();
    let mut list_t0: Option<Instant> = None;
    let mut list_seen: Option<i64> = None;
    let mut status_at = Instant::now();

    loop {
        let tick = Instant::now();
        // ---------------- data ----------------
        let (status, up, list_at) = {
            let mut f = feed.lock().unwrap();
            (f.status.take(), f.upcoming.take(), f.list_at.take())
        };
        if let Some(s) = status {
            let c = &s["current"];
            let p = c["path"].as_str().unwrap_or("").to_string();
            live = !c.is_null();
            pos = c["position_ms"].as_i64().unwrap_or(0);
            dur = c["duration_ms"].as_i64().unwrap_or(0);
            status_at = Instant::now();
            if p != path {
                path = p;
                let title = c["title"].as_str().unwrap_or("").to_string();
                let (artist, song) = split_title(&title);
                layers.big = render_big(&mut tr, &artist, &song);
                layers.pill = render_pill(&mut tr, &artist, &song);
                track_t0 = Instant::now();
                next_t0 = None;
                refresh.store(true, Ordering::SeqCst); // the queue moved on
            }
        }
        if let Some(u) = up { upcoming = u; }
        if let Some(at) = list_at {
            if list_seen.map(|s| s != at).unwrap_or(false) && at > 0 {
                // a user typed !list: build the panel from the freshest queue
                refresh.store(true, Ordering::SeqCst);
                layers.rows = std::iter::once(render_row(&mut tr, 0, ""))
                    .chain(upcoming.iter().take(LIST_ROWS).enumerate().map(|(i, t)| render_row(&mut tr, i + 1, t)))
                    .collect();
                list_t0 = Some(Instant::now());
            }
            list_seen = Some(at);
        }
        // position advances between status polls
        let pos_now = pos + status_at.elapsed().as_millis() as i64;
        let remaining = if dur > 0 { dur - pos_now } else { i64::MAX };

        // ---------------- timeline ----------------
        let t = track_t0.elapsed().as_secs_f64();
        let mut animating = false;
        out.clear();
        if live && !path.is_empty() {
            // big card -> pill
            let (bw, bh) = (layers.big.w as f32, layers.big.h as f32);
            let (pw, ph) = (layers.pill.w as f32, layers.pill.h as f32);
            if t < BIG_HOLD + MORPH {
                let fin = prog(t, 0.0, BIG_IN);
                let angle = (-75.0 + 75.0 * ease_out_back(fin)) as f32;
                let mt = prog(t, BIG_HOLD, MORPH);
                // clean handover: the big card shrinks towards the pill and fades out first,
                // then the pill pops in (slight overshoot) - no ghosting of both at once
                let shrink = ease_in_out(prog(mt, 0.0, 0.6));
                let big_scale = (1.0 - shrink as f32 * (1.0 - ph / bh)) * (0.92 + 0.08 * fin as f32);
                let big_alpha = (1.0 - shrink) as f32 * (fin * 2.5).min(1.0) as f32;
                out.draw(&layers.big, X0 + bw * big_scale / 2.0, Y0 + bh * big_scale / 2.0, big_scale, angle.to_radians(), 0.0, big_alpha, FOCAL);
                let pin = prog(mt, 0.45, 0.55);
                let m = mt;
                if pin > 0.0 {
                    let ps = (1.15 - 0.15 * ease_out_back(pin)) as f32;
                    out.draw(&layers.pill, X0 + pw * ps / 2.0, Y0 + ph * ps / 2.0, ps, 0.0, 0.0, ease_in_out(pin * 1.6) as f32, FOCAL);
                }
                animating = fin < 1.0 || m > 0.0;
            } else {
                out.draw(&layers.pill, X0 + pw / 2.0, Y0 + ph / 2.0, 1.0, 0.0, 0.0, 1.0, FOCAL);
            }

            // NEXT banner, 30 s before the end (not while the list panel is up)
            let list_up = list_t0.map(|s| s.elapsed().as_secs_f64() < LIST_HOLD + LIST_OUT + 0.5).unwrap_or(false);
            if remaining <= NEXT_AT_MS && remaining > 0 && t > BIG_HOLD + MORPH && !upcoming.is_empty() && !list_up {
                if next_t0.is_none() || next_for != upcoming[0] {
                    next_for = upcoming[0].clone();
                    layers.next = render_next(&mut tr, &next_for);
                    if next_t0.is_none() { next_t0 = Some(Instant::now()); }
                }
                let nt = next_t0.unwrap().elapsed().as_secs_f64();
                let k = prog(nt, 0.0, NEXT_IN);
                let angle = (-90.0 + 90.0 * ease_out_back(k)) as f32;
                let (nw, nh) = (layers.next.w as f32, layers.next.h as f32);
                let ny = Y0 + ph + 8.0;
                // countdown bar under the banner: shrinks to zero at the end of the track
                let mut banner = Canvas::new(layers.next.w, layers.next.h + 6);
                banner.draw(&layers.next, nw / 2.0, nh / 2.0, 1.0, 0.0, 0.0, 1.0, FOCAL);
                let frac = (remaining as f32 / NEXT_AT_MS as f32).clamp(0.0, 1.0);
                banner.round_rect(8.0, nh + 2.0, (nw - 16.0) * frac, 3.0, 1.5, PINK);
                out.draw(&banner, X0 + nw / 2.0, ny + (nh + 6.0) / 2.0, 1.0, 0.0, angle.to_radians(), (0.3 + 0.7 * k) as f32, FOCAL);
                animating |= k < 1.0;
            }

            // !list panel: rows cascade in with a flip, hold, then fold away bottom-up
            if let Some(l0) = list_t0 {
                let lt = l0.elapsed().as_secs_f64();
                if lt > LIST_HOLD + LIST_OUT + LIST_STAGGER * layers.rows.len() as f64 + 0.2 {
                    list_t0 = None;
                } else {
                    let n = layers.rows.len();
                    let mut y = Y0 + ph + 10.0;
                    for (i, row) in layers.rows.iter().enumerate() {
                        let kin = prog(lt, i as f64 * LIST_STAGGER, LIST_IN);
                        let kout = prog(lt, LIST_HOLD + (n - 1 - i) as f64 * LIST_STAGGER * 0.6, LIST_OUT);
                        let angle = if kout > 0.0 { (90.0 * ease_in_out(kout)) as f32 } else { (-90.0 + 90.0 * ease_out_back(kin)) as f32 };
                        let slide = (1.0 - ease_in_out(kin)) as f32 * -40.0;
                        let alpha = (kin.min(1.0) * (1.0 - kout)) as f32;
                        let (rw, rh) = (row.w as f32, row.h as f32);
                        out.draw(row, X0 + rw / 2.0 + slide, y + rh / 2.0, 1.0, 0.0, angle.to_radians(), alpha, FOCAL);
                        y += rh + 4.0;
                        animating |= (kin > 0.0 && kin < 1.0) || (kout > 0.0 && kout < 1.0);
                    }
                    animating |= lt < LIST_STAGGER * n as f64 + LIST_IN;
                }
            }
        }

        // ---------------- send ----------------
        if conn.is_none() && last_try.elapsed() >= Duration::from_secs(2) {
            last_try = Instant::now();
            conn = TcpStream::connect(&a.target).ok();
            if conn.is_some() { eprintln!("connected to {}", a.target); }
        }
        if animating {
            if let Ok(dir) = std::env::var("OVERLAY_DUMP") {
                // debug: keep the animation frames as raw RGBA for inspection
                let n = DUMPED.with(|d| { let v = d.get(); d.set(v + 1); v });
                let _ = std::fs::write(format!("{dir}/f{n:05}.rgba"), &out.px);
            }
        }
        if let Some(c) = conn.as_mut() {
            if c.write_all(&out.px).is_err() {
                eprintln!("overlay connection lost");
                conn = None;
            }
        }
        let next_up = remaining <= NEXT_AT_MS && remaining > 0;
        let fps: u64 = if animating { 30 } else if next_up { 10 } else { 2 };
        if let Some(rest) = Duration::from_micros(1_000_000 / fps).checked_sub(tick.elapsed()) { std::thread::sleep(rest); }
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
        assert_eq!(ease_in_out(0.0), 0.0);
        assert_eq!(ease_in_out(1.0), 1.0);
    }

    #[test]
    fn draw_at_rest_is_identity() {
        let mut card = Canvas::new(10, 6);
        card.round_rect(0.0, 0.0, 10.0, 6.0, 0.0, Rgba(200, 10, 10, 255));
        let mut out = Canvas::new(10, 6);
        out.draw(&card, 5.0, 3.0, 1.0, 0.0, 0.0, 1.0, 900.0);
        assert_eq!(&out.px[(3 * 10 + 5) * 4..(3 * 10 + 5) * 4 + 4], &[200, 10, 10, 255]);
    }
}
