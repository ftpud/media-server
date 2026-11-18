//! Tiny RGBA software canvas: rounded rects, gradients, text (fontdue), and a perspective
//! "card flip" warp (rotation around the vertical axis with a pinhole projection).
use anyhow::{anyhow, Result};
use std::collections::HashMap;

#[derive(Clone, Copy)]
pub struct Rgba(pub u8, pub u8, pub u8, pub u8);

pub struct Canvas {
    pub w: usize,
    pub h: usize,
    pub px: Vec<u8>, // straight (non-premultiplied) RGBA
}

impl Canvas {
    pub fn new(w: usize, h: usize) -> Canvas { Canvas { w, h, px: vec![0; w * h * 4] } }
    pub fn clear(&mut self) { self.px.iter_mut().for_each(|p| *p = 0); }

    /// "source over" blend of a color with coverage (0..1) into pixel (x, y).
    #[inline]
    pub fn blend(&mut self, x: usize, y: usize, c: Rgba, cov: f32) {
        let a = (c.3 as f32 / 255.0) * cov;
        if a <= 0.0 { return; }
        let i = (y * self.w + x) * 4;
        let da = self.px[i + 3] as f32 / 255.0;
        let oa = a + da * (1.0 - a);
        if oa <= 0.0 { return; }
        for k in 0..3 {
            let s = [c.0, c.1, c.2][k] as f32;
            let d = self.px[i + k] as f32;
            self.px[i + k] = ((s * a + d * da * (1.0 - a)) / oa).round() as u8;
        }
        self.px[i + 3] = (oa * 255.0).round() as u8;
    }

    pub fn round_rect(&mut self, x: f32, y: f32, w: f32, h: f32, r: f32, c: Rgba) {
        let (x0, y0) = (x.max(0.0) as usize, y.max(0.0) as usize);
        let (x1, y1) = (((x + w).ceil() as usize).min(self.w), ((y + h).ceil() as usize).min(self.h));
        for py in y0..y1 {
            for px in x0..x1 {
                // distance to the rounded rectangle edge, for an anti-aliased border
                let fx = px as f32 + 0.5;
                let fy = py as f32 + 0.5;
                let qx = (fx - (x + w / 2.0)).abs() - (w / 2.0 - r);
                let qy = (fy - (y + h / 2.0)).abs() - (h / 2.0 - r);
                let d = (qx.max(0.0).powi(2) + qy.max(0.0).powi(2)).sqrt() + qx.max(qy).min(0.0) - r;
                let cov = (0.5 - d).clamp(0.0, 1.0);
                self.blend(px, py, c, cov);
            }
        }
    }

    pub fn gradient_rect(&mut self, x: f32, y: f32, w: f32, h: f32, a: Rgba, b: Rgba) {
        if w <= 0.0 { return; }
        let (x0, y0) = (x.max(0.0) as usize, y.max(0.0) as usize);
        let (x1, y1) = (((x + w).ceil() as usize).min(self.w), ((y + h).ceil() as usize).min(self.h));
        for py in y0..y1 {
            for px in x0..x1 {
                let t = ((px as f32 - x) / w.max(1.0) + (py as f32 - y) / h.max(1.0) * 0.3).clamp(0.0, 1.0);
                let l = |p: u8, q: u8| (p as f32 + (q as f32 - p as f32) * t) as u8;
                self.blend(px, py, Rgba(l(a.0, b.0), l(a.1, b.1), l(a.2, b.2), l(a.3, b.3)), 1.0);
            }
        }
    }

    /// Draws `card` rotated by `angle` (radians) around its vertical center axis, centered at
    /// (cx, cy), with a pinhole camera at focal distance `f`. Inverse mapping per pixel, bilinear.
    pub fn warp_card(&mut self, card: &Canvas, angle: f32, cx: f32, cy: f32, f: f32) {
        let (sin, cos) = angle.sin_cos();
        if cos.abs() < 1e-3 { return; } // edge-on
        let (hw, hh) = (card.w as f32 / 2.0, card.h as f32 / 2.0);
        let shade = 0.55 + 0.45 * cos.abs(); // darker as it turns away
        for y in 0..self.h {
            let v = y as f32 + 0.5 - cy;
            for x in 0..self.w {
                let u = x as f32 + 0.5 - cx;
                let den = f * cos - u * sin;
                if den.abs() < 1e-3 { continue; }
                let xs = u * f / den;                 // card-local x (before projection)
                let ys = v * (f + xs * sin) / f;      // card-local y
                let (sx, sy) = (xs + hw - 0.5, ys + hh - 0.5);
                if sx < -1.0 || sy < -1.0 || sx >= card.w as f32 || sy >= card.h as f32 { continue; }
                let p = card.sample(sx, sy);
                if p[3] == 0 { continue; }
                let c = Rgba((p[0] as f32 * shade) as u8, (p[1] as f32 * shade) as u8, (p[2] as f32 * shade) as u8, p[3]);
                self.blend(x, y, c, 1.0);
            }
        }
    }

    fn sample(&self, x: f32, y: f32) -> [u8; 4] {
        let (x0, y0) = (x.floor(), y.floor());
        let (fx, fy) = (x - x0, y - y0);
        let get = |xi: i32, yi: i32| -> [f32; 4] {
            if xi < 0 || yi < 0 || xi >= self.w as i32 || yi >= self.h as i32 { return [0.0; 4]; }
            let i = (yi as usize * self.w + xi as usize) * 4;
            let a = self.px[i + 3] as f32;
            // premultiply for correct filtering at transparent edges
            [self.px[i] as f32 * a / 255.0, self.px[i + 1] as f32 * a / 255.0, self.px[i + 2] as f32 * a / 255.0, a]
        };
        let (xi, yi) = (x0 as i32, y0 as i32);
        let (a, b, c, d) = (get(xi, yi), get(xi + 1, yi), get(xi, yi + 1), get(xi + 1, yi + 1));
        let mut o = [0.0f32; 4];
        for k in 0..4 {
            o[k] = (a[k] * (1.0 - fx) + b[k] * fx) * (1.0 - fy) + (c[k] * (1.0 - fx) + d[k] * fx) * fy;
        }
        let alpha = o[3];
        if alpha <= 0.5 { return [0, 0, 0, 0]; }
        [(o[0] * 255.0 / alpha) as u8, (o[1] * 255.0 / alpha) as u8, (o[2] * 255.0 / alpha) as u8, alpha as u8]
    }
}

pub struct TextRenderer {
    font: fontdue::Font,
    cache: HashMap<(char, u32), (fontdue::Metrics, Vec<u8>)>,
}

impl TextRenderer {
    pub fn new(bytes: &[u8]) -> Result<TextRenderer> {
        let font = fontdue::Font::from_bytes(bytes, fontdue::FontSettings { collection_index: 0, scale: 40.0, ..Default::default() })
            .map_err(|e| anyhow!("font: {e}"))?;
        Ok(TextRenderer { font, cache: HashMap::new() })
    }

    fn glyph(&mut self, ch: char, size: f32) -> &(fontdue::Metrics, Vec<u8>) {
        let font = &self.font;
        self.cache.entry((ch, (size * 4.0) as u32)).or_insert_with(|| font.rasterize(ch, size))
    }

    pub fn width(&mut self, text: &str, size: f32) -> f32 {
        text.chars().map(|c| self.glyph(c, size).0.advance_width).sum()
    }

    /// Single line at top-left (x, y), cut with an ellipsis to `max_w`. Returns the drawn width.
    pub fn text(&mut self, c: &mut Canvas, text: &str, x: f32, y: f32, size: f32, color: Rgba, max_w: f32) -> f32 {
        let ascent = self.font.horizontal_line_metrics(size).map(|m| m.ascent).unwrap_or(size * 0.8);
        let mut s: String = text.to_string();
        if self.width(&s, size) > max_w {
            while !s.is_empty() && self.width(&(s.clone() + "…"), size) > max_w { s.pop(); }
            s.push('…');
        }
        let mut pen = x;
        for ch in s.chars() {
            let (m, bmp) = self.glyph(ch, size).clone();
            let gx = (pen + m.xmin as f32).round() as i32;
            let gy = (y + ascent - m.height as f32 - m.ymin as f32).round() as i32;
            for row in 0..m.height {
                for col in 0..m.width {
                    let cov = bmp[row * m.width + col];
                    if cov == 0 { continue; }
                    let (px, py) = (gx + col as i32, gy + row as i32);
                    if px < 0 || py < 0 || px >= c.w as i32 || py >= c.h as i32 { continue; }
                    c.blend(px as usize, py as usize, color, cov as f32 / 255.0);
                }
            }
            pen += m.advance_width;
        }
        pen - x
    }
}
