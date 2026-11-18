//! Overlay input: an external renderer (3D titles, chat, upcoming tracks...) connects over TCP
//! and streams raw RGBA frames of a fixed size. mediad keeps only the latest frame and
//! alpha-blends it onto every output frame at a fixed position. No client = no overlay.
//!
//! Config: MEDIAD_OVERLAY=<listen addr>:<port>:<w>x<h>+<x>+<y>, e.g. 127.0.0.1:8096:640x200+20+60
use std::io::Read;
use std::net::TcpListener;
use std::sync::{Arc, Mutex};

pub struct OverlayCfg {
    pub addr: String,
    pub w: usize,
    pub h: usize,
    pub x: usize,
    pub y: usize,
}

impl OverlayCfg {
    pub fn parse(s: &str) -> Option<OverlayCfg> {
        // host:port:WxH+X+Y
        let (addr, geo) = s.rsplit_once(':')?;
        let (wh, rest) = geo.split_once('+')?;
        let (x, y) = rest.split_once('+')?;
        let (w, h) = wh.split_once('x')?;
        Some(OverlayCfg { addr: addr.to_string(), w: w.parse().ok()?, h: h.parse().ok()?, x: x.parse().ok()?, y: y.parse().ok()? })
    }
}

/// Overlay converted to YUV (BT.709, limited range) with straight alpha, full resolution.
pub struct OvFrame {
    pub w: usize,
    pub h: usize,
    pub y: Vec<u8>,
    pub u: Vec<u8>,
    pub v: Vec<u8>,
    pub a: Vec<u8>,
}

fn convert(rgba: &[u8], w: usize, h: usize) -> OvFrame {
    let n = w * h;
    let (mut y, mut u, mut v, mut a) = (vec![0u8; n], vec![0u8; n], vec![0u8; n], vec![0u8; n]);
    for i in 0..n {
        let (r, g, b, al) = (rgba[4 * i] as i32, rgba[4 * i + 1] as i32, rgba[4 * i + 2] as i32, rgba[4 * i + 3]);
        y[i] = (16 + ((47 * r + 157 * g + 16 * b) >> 8)).clamp(0, 255) as u8;
        u[i] = (128 + ((-26 * r - 87 * g + 112 * b) >> 8)).clamp(0, 255) as u8;
        v[i] = (128 + ((112 * r - 102 * g - 10 * b) >> 8)).clamp(0, 255) as u8;
        a[i] = al;
    }
    OvFrame { w, h, y, u, v, a }
}

pub type Latest = Arc<Mutex<Option<Arc<OvFrame>>>>;

pub fn listen(cfg: &OverlayCfg) -> std::io::Result<Latest> {
    let latest: Latest = Arc::new(Mutex::new(None));
    let l = TcpListener::bind(&cfg.addr)?;
    eprintln!("overlay input on tcp://{} ({}x{} RGBA)", cfg.addr, cfg.w, cfg.h);
    let (w, h, out) = (cfg.w, cfg.h, latest.clone());
    std::thread::spawn(move || {
        for c in l.incoming().flatten() {
            let mut c = c;
            // Optional handshake "OVL1" + width + height (u32 LE): the client decides its size.
            // Without it (older clients) the configured size is used and the 4 bytes are pixels.
            let mut magic = [0u8; 4];
            if c.read_exact(&mut magic).is_err() { continue; }
            let (mut fw, mut fh, mut pre) = (w, h, magic.to_vec());
            if &magic == b"OVL1" {
                let mut dims = [0u8; 8];
                if c.read_exact(&mut dims).is_err() { continue; }
                let cw = u32::from_le_bytes(dims[0..4].try_into().unwrap()) as usize;
                let ch = u32::from_le_bytes(dims[4..8].try_into().unwrap()) as usize;
                if cw == 0 || ch == 0 || cw * ch > 4096 * 4096 { continue; }
                fw = cw;
                fh = ch;
                pre.clear();
            }
            eprintln!("overlay client connected ({fw}x{fh})");
            let mut buf = vec![0u8; fw * fh * 4];
            loop {
                let start = pre.len();
                buf[..start].copy_from_slice(&pre);
                pre.clear();
                if c.read_exact(&mut buf[start..]).is_err() { break; }
                *out.lock().unwrap() = Some(Arc::new(convert(&buf, fw, fh)));
            }
            *out.lock().unwrap() = None;
            eprintln!("overlay client disconnected");
        }
    });
    Ok(latest)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_overlay_geometry() {
        let c = OverlayCfg::parse("127.0.0.1:8096:600x220+1290+40").unwrap();
        assert_eq!((c.addr.as_str(), c.w, c.h, c.x, c.y), ("127.0.0.1:8096", 600, 220, 1290, 40));
        assert!(OverlayCfg::parse("garbage").is_none());
    }

    #[test]
    fn converts_white_and_black_to_limited_range() {
        let f = convert(&[255, 255, 255, 255, 0, 0, 0, 128], 2, 1);
        assert!((233..=236).contains(&f.y[0]), "white Y={}", f.y[0]);
        assert_eq!(f.y[1], 16);
        assert_eq!((f.a[0], f.a[1]), (255, 128));
    }
}
