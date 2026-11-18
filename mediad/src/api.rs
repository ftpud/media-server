//! Shared state + a tiny dependency-free HTTP control API on localhost.
//!
//!   GET /load?path=..&start_ms=..&gain_db=..&title=..&subs=..&now=1   queue a track (now=1: replace current)
//!   GET /skip            abort the current track
//!   GET /seek?ms=..      restart the current track at an offset
//!   GET /clear           drop the queue
//!   GET /status          current state as JSON
//!   GET /events?since=N&wait=ms   long-poll the event log (track_started/ending/ended/error, connected...)
//!   GET /health
use serde_json::{json, Value};
use std::collections::VecDeque;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

#[derive(Clone, Debug, Default)]
pub struct TrackReq {
    pub path: String,
    pub title: String,
    pub start_ms: i64,
    pub gain_db: f64,
    pub subs: Option<String>,
}

#[derive(Default)]
pub struct State {
    pub queue: VecDeque<TrackReq>,
    pub current: Option<TrackReq>,
    pub duration_ms: i64,
    pub events: VecDeque<(u64, Value)>,
    pub next_id: u64,
    pub connected: bool,
    pub filler: bool,
}

pub struct Shared {
    pub st: Mutex<State>,
    pub cv: Condvar,
    pub skip: AtomicBool,
    pub clear_next: AtomicBool,
    pub seek_ms: AtomicI64, // -1 = none
    pub pos_ms: AtomicI64,
    pub out_ms: AtomicI64,
    pub av_ms: AtomicI64,
    pub started: Instant,
}

impl Shared {
    pub fn new() -> Arc<Self> {
        Arc::new(Shared {
            st: Mutex::new(State { next_id: 1, ..Default::default() }),
            cv: Condvar::new(),
            skip: AtomicBool::new(false),
            clear_next: AtomicBool::new(false),
            seek_ms: AtomicI64::new(-1),
            pos_ms: AtomicI64::new(0),
            out_ms: AtomicI64::new(0),
            av_ms: AtomicI64::new(0),
            started: Instant::now(),
        })
    }
    pub fn emit(&self, mut ev: Value) {
        let mut s = self.st.lock().unwrap();
        let id = s.next_id;
        s.next_id += 1;
        ev["id"] = json!(id);
        ev["uptime_s"] = json!(self.started.elapsed().as_secs());
        eprintln!("event {ev}");
        s.events.push_back((id, ev));
        while s.events.len() > 500 {
            s.events.pop_front();
        }
        self.cv.notify_all();
    }
    pub fn pop_track(&self) -> Option<TrackReq> {
        self.st.lock().unwrap().queue.pop_front()
    }
}

fn urldecode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                    out.push(v);
                    i += 3;
                    continue;
                }
                out.push(b'%');
            }
            b'+' => out.push(b' '),
            c => out.push(c),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn query(q: &str) -> std::collections::HashMap<String, String> {
    q.split('&')
        .filter(|p| !p.is_empty())
        .map(|p| {
            let mut it = p.splitn(2, '=');
            (urldecode(it.next().unwrap_or("")), urldecode(it.next().unwrap_or("")))
        })
        .collect()
}

fn respond(mut s: TcpStream, code: &str, body: &Value) {
    let b = body.to_string();
    let _ = write!(s, "HTTP/1.1 {code}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{b}", b.len());
}

fn handle(mut s: TcpStream, sh: Arc<Shared>) {
    let _ = s.set_read_timeout(Some(Duration::from_secs(5)));
    let mut buf = [0u8; 8192];
    let n = match s.read(&mut buf) { Ok(n) if n > 0 => n, _ => return };
    let req = String::from_utf8_lossy(&buf[..n]).into_owned();
    let line = req.lines().next().unwrap_or("");
    let target = line.split_whitespace().nth(1).unwrap_or("/");
    let (path, q) = target.split_once('?').unwrap_or((target, ""));
    let p = query(q);
    let num = |k: &str, d: i64| p.get(k).and_then(|v| v.parse().ok()).unwrap_or(d);
    match path {
        "/health" => respond(s, "200 OK", &json!({"status": "UP"})),
        "/load" => {
            let Some(pa) = p.get("path").filter(|v| !v.is_empty()) else {
                return respond(s, "400 Bad Request", &json!({"error": "path required"}));
            };
            let title = p.get("title").cloned().unwrap_or_else(|| {
                std::path::Path::new(pa).file_stem().map(|x| x.to_string_lossy().into_owned()).unwrap_or_default()
            });
            let t = TrackReq {
                path: pa.clone(),
                title,
                start_ms: num("start_ms", 0),
                gain_db: p.get("gain_db").and_then(|v| v.parse().ok()).unwrap_or(0.0),
                subs: p.get("subs").filter(|v| !v.is_empty()).cloned(),
            };
            let now = num("now", 0) == 1;
            let mut st = sh.st.lock().unwrap();
            if now {
                st.queue.push_front(t);
                sh.skip.store(true, Ordering::SeqCst);
            } else {
                st.queue.push_back(t);
            }
            let ql = st.queue.len();
            drop(st);
            sh.cv.notify_all();
            respond(s, "200 OK", &json!({"queued": ql}));
        }
        "/skip" => {
            sh.skip.store(true, Ordering::SeqCst);
            respond(s, "200 OK", &json!({"ok": true}));
        }
        "/seek" => {
            sh.seek_ms.store(num("ms", 0).max(0), Ordering::SeqCst);
            respond(s, "200 OK", &json!({"ok": true}));
        }
        "/clear" => {
            sh.st.lock().unwrap().queue.clear();
            sh.clear_next.store(true, Ordering::SeqCst);
            respond(s, "200 OK", &json!({"ok": true}));
        }
        "/status" => {
            let st = sh.st.lock().unwrap();
            let cur = st.current.as_ref().map(|c| json!({
                "path": c.path, "title": c.title,
                "position_ms": sh.pos_ms.load(Ordering::SeqCst), "duration_ms": st.duration_ms}));
            let queue: Vec<Value> = st.queue.iter().map(|t| json!({"path": t.path, "title": t.title})).collect();
            let body = json!({"current": cur, "queue": queue, "connected": st.connected,
                "filler": st.filler, "last_event_id": st.next_id - 1,
                "output_ms": sh.out_ms.load(Ordering::Relaxed), "audio_minus_video_ms": sh.av_ms.load(Ordering::Relaxed)});
            drop(st);
            respond(s, "200 OK", &body);
        }
        "/events" => {
            let since = num("since", 0) as u64;
            let wait = Duration::from_millis(num("wait", 0).clamp(0, 30_000) as u64);
            let deadline = Instant::now() + wait;
            let mut st = sh.st.lock().unwrap();
            loop {
                let evs: Vec<Value> = st.events.iter().filter(|(id, _)| *id > since).map(|(_, e)| e.clone()).collect();
                let now = Instant::now();
                if !evs.is_empty() || now >= deadline {
                    let next = st.next_id - 1;
                    drop(st);
                    return respond(s, "200 OK", &json!({"events": evs, "next": next}));
                }
                st = sh.cv.wait_timeout(st, deadline - now).unwrap().0;
            }
        }
        _ => respond(s, "404 Not Found", &json!({"error": "not found"})),
    }
}

pub fn serve(sh: Arc<Shared>, addr: &str) -> std::io::Result<()> {
    let l = TcpListener::bind(addr)?;
    eprintln!("control API on http://{addr}");
    std::thread::spawn(move || {
        for c in l.incoming().flatten() {
            let sh = sh.clone();
            std::thread::spawn(move || handle(c, sh));
        }
    });
    Ok(())
}
