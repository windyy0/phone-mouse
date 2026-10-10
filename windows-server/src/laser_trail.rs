//! Bounded CPU rendering for a native cursor image, independent of Win32.
use crate::protocol::LaserSettings;
use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};

const MAX_SIDE: usize = 256;
const MAX_SAMPLES: usize = 256;
const MAX_CURVE_STEPS: usize = 256;
const MOTION_WINDOW: Duration = Duration::from_millis(120);

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Position {
    pub x: i32,
    pub y: i32,
}
#[derive(Clone, Copy, Debug)]
struct Point {
    x: f64,
    y: f64,
}
impl Point {
    fn mix(self, other: Self, t: f64) -> Self {
        Self {
            x: self.x + (other.x - self.x) * t,
            y: self.y + (other.y - self.y) * t,
        }
    }
    fn distance(self, other: Self) -> f64 {
        (self.x - other.x).hypot(self.y - other.y)
    }
}
impl From<Position> for Point {
    fn from(value: Position) -> Self {
        Self {
            x: f64::from(value.x),
            y: f64::from(value.y),
        }
    }
}
struct Sample {
    point: Point,
    at: Instant,
}
#[derive(Clone, Copy)]
struct Vertex {
    point: Point,
    age: f64,
}
impl Vertex {
    fn mix(self, other: Self, t: f64) -> Self {
        Self {
            point: self.point.mix(other.point, t),
            age: self.age + (other.age - self.age) * t,
        }
    }
}
pub struct Frame {
    pub left: i32,
    pub top: i32,
    pub width: usize,
    pub height: usize,
    pub pixels: Vec<u32>,
}
pub struct Trail {
    settings: LaserSettings,
    samples: VecDeque<Sample>,
    position: Option<Position>,
    dpi_scale: f64,
    dirty: bool,
    sampled_at: Option<Instant>,
    moved_at: Option<Instant>,
    tail_start: Option<Instant>,
}
impl Trail {
    pub fn new(settings: LaserSettings) -> Self {
        Self {
            settings,
            samples: VecDeque::new(),
            position: None,
            dpi_scale: 1.0,
            dirty: true,
            sampled_at: None,
            moved_at: None,
            tail_start: None,
        }
    }
    pub fn configure(&mut self, settings: LaserSettings) {
        if settings.color != self.settings.color
            || settings.size_percent != self.settings.size_percent
            || settings.trail != self.settings.trail
        {
            self.clear();
        }
        self.settings = settings;
        self.trim_length();
        self.dirty = true;
    }
    pub fn clear(&mut self) {
        self.samples.clear();
        self.sampled_at = None;
        self.moved_at = None;
        self.tail_start = None;
        self.dirty = true;
    }
    fn scale(&self) -> f64 {
        // Even unusual DPI values cannot allocate an unbounded native cursor.
        (self.dpi_scale * f64::from(self.settings.size_percent) / 100.0).min(12.7)
    }
    fn radius(&self) -> i32 {
        (10.0 * self.scale()).ceil() as i32
    }
    fn maximum_length(&self) -> f64 {
        ((MAX_SIDE as f64 - 2.0 * f64::from(self.radius()) - 2.0).max(0.0))
            .min(f64::from(self.settings.trail_length_px) * self.dpi_scale)
    }
    fn trim_length(&mut self) {
        let maximum = self.maximum_length();
        let mut length = 0.0;
        for index in (1..self.samples.len()).rev() {
            let a = &self.samples[index - 1];
            let b = &self.samples[index];
            let segment = a.point.distance(b.point);
            if length + segment > maximum {
                // Record the boundary, but retain real samples before it for
                // curvature. Clipping samples first reduced fast tails to two
                // points, so smoothing could only produce a straight chord.
                let t = if segment == 0.0 {
                    1.0
                } else {
                    1.0 - (maximum - length) / segment
                };
                let at = a.at
                    + b.at
                        .saturating_duration_since(a.at)
                        .mul_f64(t.clamp(0.0, 1.0));
                self.tail_start = Some(self.tail_start.map_or(at, |old| old.max(at)));
                for _ in 0..index.saturating_sub(2) {
                    self.samples.pop_front();
                }
                break;
            }
            length += segment;
        }
        while self.samples.len() > MAX_SAMPLES {
            self.samples.pop_front();
        }
    }
    fn trim_age(&mut self, now: Instant) {
        let lifetime = Duration::from_millis(u64::from(self.settings.fade_ms));
        let Some(cutoff) = now.checked_sub(lifetime) else {
            return;
        };
        while self.samples.len() > 1 && self.samples[1].at <= cutoff {
            self.samples.pop_front();
        }
        if let Some(first) = self.samples.front() {
            if first.at <= cutoff {
                if self.samples.len() == 1 {
                    self.samples.clear();
                } else {
                    let next = &self.samples[1];
                    let t = cutoff.saturating_duration_since(first.at).as_secs_f64()
                        / next.at.saturating_duration_since(first.at).as_secs_f64();
                    let point = first.point.mix(next.point, t.clamp(0.0, 1.0));
                    *self.samples.front_mut().unwrap() = Sample { point, at: cutoff };
                }
            }
        }
        if self.samples.is_empty() {
            self.tail_start = None;
        }
    }
    pub fn update(&mut self, position: Position, dpi: u32, now: Instant) -> bool {
        let scale = f64::from(dpi.max(96)) / 96.0;
        if scale != self.dpi_scale {
            self.dpi_scale = scale;
            self.clear();
        }
        let moved = self.position != Some(position);
        if moved {
            self.dirty |= self.position.is_none() || self.settings.trail;
            // Anchor resumed movement to the last stationary observation,
            // rather than interpolating it over the whole idle pause.
            if let (Some(previous), Some(sample)) = (self.sampled_at, self.samples.back()) {
                if previous > sample.at {
                    self.samples.push_back(Sample {
                        point: sample.point,
                        at: previous,
                    });
                }
            }
            self.position = Some(position);
            self.moved_at = Some(now);
            if self.settings.trail {
                self.samples.push_back(Sample {
                    point: position.into(),
                    at: now,
                });
                self.trim_length();
            }
        }
        self.sampled_at = Some(now);
        let had_trail = !self.samples.is_empty();
        self.trim_age(now);
        (std::mem::take(&mut self.dirty) || had_trail) && self.position.is_some()
    }
    fn path(&self, now: Instant) -> Vec<Vertex> {
        // Length comes from actual recent travel, without a second velocity
        // estimator and spring-like spatial clipping. Freeze at the last
        // motion while the independent, configured age fade removes it.
        let cutoff = self
            .moved_at
            .and_then(|at| at.checked_sub(MOTION_WINDOW))
            .into_iter()
            .chain(self.tail_start)
            .max();
        let spacing = (self.scale() * 0.75).max(0.75);
        // Two older points supply the heading across the time boundary. Do
        // not repeatedly flatten the entire fade history on every frame.
        let start = cutoff
            .map_or(0, |cutoff| {
                self.samples
                    .iter()
                    .position(|sample| sample.at >= cutoff)
                    .unwrap_or(self.samples.len())
            })
            .saturating_sub(2);
        let mut path = smooth_path(self.samples.iter().skip(start), now, spacing);
        if let Some(cutoff) = cutoff {
            clip_age(
                &mut path,
                now.saturating_duration_since(cutoff).as_secs_f64(),
            );
        }
        clip_path(&mut path, self.maximum_length());
        resample_path(&path, spacing)
    }
    pub fn frame(&self, now: Instant) -> Frame {
        let position = self.position.expect("cursor sampled before rendering");
        let radius = self.radius();
        let path = self.path(now);
        let mut min: Point = position.into();
        let mut max = min;
        for vertex in &path {
            min.x = min.x.min(vertex.point.x);
            min.y = min.y.min(vertex.point.y);
            max.x = max.x.max(vertex.point.x);
            max.y = max.y.max(vertex.point.y);
        }
        // Fractional clip boundaries round inward only in the transparent
        // padding; they never move the actual mouse hotspot.
        let left = (min.x.floor() as i32).saturating_sub(radius);
        let top = (min.y.floor() as i32).saturating_sub(radius);
        let width = ((max.x.ceil() as i64) - i64::from(left) + i64::from(radius) + 1) as usize;
        let height = ((max.y.ceil() as i64) - i64::from(top) + i64::from(radius) + 1) as usize;
        let mut frame = Frame {
            left,
            top,
            width,
            height,
            pixels: vec![0; width * height],
        };
        let total: f64 = path
            .windows(2)
            .map(|p| p[0].point.distance(p[1].point))
            .sum();
        let lifetime = f64::from(self.settings.fade_ms) / 1000.0;
        let mut distance = 0.0;
        for pair in path.windows(2) {
            let length = pair[0].point.distance(pair[1].point);
            let style = |vertex: Vertex, along: f64| {
                let u = if total > 0.0 {
                    (along / total).clamp(0.0, 1.0)
                } else {
                    1.0
                };
                let age = (1.0 - vertex.age / lifetime).clamp(0.0, 1.0);
                let end = (u * 3.0).clamp(0.0, 1.0);
                let opacity = age.powf(1.2) * end * end * (3.0 - 2.0 * end);
                (1.5 * self.scale() * (0.1 + 0.9 * u.sqrt()), opacity)
            };
            let a = style(pair[0], distance);
            distance += length;
            let b = style(pair[1], distance);
            stroke(
                &mut frame,
                pair[0].point,
                pair[1].point,
                a,
                b,
                self.settings.color,
            );
        }
        point(
            &mut frame,
            position.into(),
            self.scale(),
            self.settings.color,
        );
        frame
    }
}

fn clip_age(path: &mut Vec<Vertex>, maximum_age: f64) {
    let start = path
        .iter()
        .position(|v| v.age <= maximum_age)
        .unwrap_or(path.len());
    if start > 0 && start < path.len() {
        let a = path[start - 1];
        let b = path[start];
        let t = (a.age - maximum_age) / (a.age - b.age);
        let boundary = a.mix(b, t.clamp(0.0, 1.0));
        path.drain(..start);
        path.insert(0, boundary);
    } else if start == path.len() {
        path.clear();
    }
}

fn clip_path(path: &mut Vec<Vertex>, maximum: f64) {
    let mut length = 0.0;
    for index in (1..path.len()).rev() {
        let a = path[index - 1];
        let b = path[index];
        let segment = a.point.distance(b.point);
        if length + segment > maximum {
            let t = 1.0 - (maximum - length) / segment;
            let boundary = a.mix(b, t.clamp(0.0, 1.0));
            path.drain(..index);
            path.insert(0, boundary);
            return;
        }
        length += segment;
    }
}

fn smooth_path<'a>(
    samples: impl Iterator<Item = &'a Sample>,
    now: Instant,
    spacing: f64,
) -> Vec<Vertex> {
    let input: Vec<_> = samples
        .map(|s| Vertex {
            point: s.point,
            age: now.saturating_duration_since(s.at).as_secs_f64(),
        })
        .collect();
    if input.len() < 3 {
        return input;
    }
    let mut flattened = vec![input[0]];
    for index in 1..input.len() - 1 {
        let a = input[index - 1].mix(input[index], 0.5);
        let b = input[index];
        let c = b.mix(input[index + 1], 0.5);
        if index == 1 {
            flattened.push(a);
        }
        let steps = ((a.point.distance(b.point) + b.point.distance(c.point)) / spacing)
            .ceil()
            .clamp(1.0, MAX_CURVE_STEPS as f64) as usize;
        for step in 1..=steps {
            let t = step as f64 / steps as f64;
            flattened.push(a.mix(b, t).mix(b.mix(c, t), t));
        }
    }
    // Continue the final turn up to the actual hotspot. The old straight
    // midpoint-to-head segment occupied most of a short, fast-moving tail.
    // Only curve control points change; the real endpoint is never predicted.
    let previous = input[input.len() - 3];
    let middle = input[input.len() - 2];
    let head = *input.last().unwrap();
    let a = *flattened.last().unwrap();
    let last_length = middle.point.distance(head.point);
    let previous_length = previous.point.distance(middle.point);
    if last_length > 0.0 && previous_length > 0.0 {
        let last_direction = Point {
            x: (head.point.x - middle.point.x) / last_length,
            y: (head.point.y - middle.point.y) / last_length,
        };
        let mut tangent = Point {
            x: 2.0 * last_direction.x - (middle.point.x - previous.point.x) / previous_length,
            y: 2.0 * last_direction.y - (middle.point.y - previous.point.y) / previous_length,
        };
        let length = tangent.x.hypot(tangent.y);
        tangent.x /= length;
        tangent.y /= length;
        let mut b = a.mix(head, 1.0 / 3.0);
        let mut c = a.mix(head, 2.0 / 3.0);
        b.point = Point {
            x: a.point.x + last_direction.x * last_length / 6.0,
            y: a.point.y + last_direction.y * last_length / 6.0,
        };
        // Clamp the visual handles to observed bounds: a sharp reversal must
        // not create a loop, an overshoot, or a trail beyond the sampled path.
        c.point = Point {
            x: (head.point.x - tangent.x * last_length / 6.0).clamp(
                previous.point.x.min(middle.point.x).min(head.point.x),
                previous.point.x.max(middle.point.x).max(head.point.x),
            ),
            y: (head.point.y - tangent.y * last_length / 6.0).clamp(
                previous.point.y.min(middle.point.y).min(head.point.y),
                previous.point.y.max(middle.point.y).max(head.point.y),
            ),
        };
        let steps =
            ((a.point.distance(b.point) + b.point.distance(c.point) + c.point.distance(head.point))
                / spacing)
                .ceil()
                .clamp(1.0, MAX_CURVE_STEPS as f64) as usize;
        for step in 1..steps {
            let t = step as f64 / steps as f64;
            let ab = a.mix(b, t);
            let bc = b.mix(c, t);
            let cd = c.mix(head, t);
            flattened.push(ab.mix(bc, t).mix(bc.mix(cd, t), t));
        }
        flattened.push(head);
    } else {
        flattened.push(head);
    }
    flattened
}

fn resample_path(flattened: &[Vertex], spacing: f64) -> Vec<Vertex> {
    if flattened.is_empty() {
        return Vec::new();
    }
    // Equal-distance samples avoid visible changes with report frequency or
    // speed. Resample only after time and length clipping to bound allocation,
    // even for very long moves. Continuous capsules fill the joins.
    let mut output = vec![flattened[0]];
    let mut until_next = spacing;
    for pair in flattened.windows(2) {
        let mut a = pair[0];
        let b = pair[1];
        let mut remaining = a.point.distance(b.point);
        while remaining >= until_next && remaining > 0.0 {
            a = a.mix(b, until_next / remaining);
            output.push(a);
            remaining = a.point.distance(b.point);
            until_next = spacing;
        }
        until_next -= remaining;
    }
    output.push(*flattened.last().unwrap());
    output
}

fn pixel(frame: &mut Frame, column: usize, row: usize, opacity: f64, color: u32) {
    // Extremely faint fringes look dusty on light backgrounds.
    let alpha = if opacity < 0.02 {
        0
    } else {
        (opacity.clamp(0.0, 1.0) * 255.0).round() as u32
    };
    let value = &mut frame.pixels[row * frame.width + column];
    // A single selected color uses max coverage, avoiding dark seams or a
    // buildup of opacity at corners while retaining premultiplied alpha.
    let candidate = (alpha << 24)
        | (((((color >> 16) & 255) * alpha + 127) / 255) << 16)
        | (((((color >> 8) & 255) * alpha + 127) / 255) << 8)
        | (((color & 255) * alpha + 127) / 255);
    if alpha > (*value >> 24) {
        *value = candidate;
    }
}
fn bright_color(color: u32, light: f64) -> u32 {
    let channel = |shift: u32| {
        let value = (color >> shift) & 255u32;
        (f64::from(value) + (255.0 - f64::from(value)) * light.clamp(0.0, 1.0)).round() as u32
    };
    (channel(16) << 16) | (channel(8) << 8) | channel(0)
}
fn stroke(
    frame: &mut Frame,
    a: Point,
    b: Point,
    style_a: (f64, f64),
    style_b: (f64, f64),
    color: u32,
) {
    let offset = Point {
        x: f64::from(frame.left),
        y: f64::from(frame.top),
    };
    let a = Point {
        x: a.x - offset.x,
        y: a.y - offset.y,
    };
    let b = Point {
        x: b.x - offset.x,
        y: b.y - offset.y,
    };
    let padding = style_a.0.max(style_b.0) * 2.4 + 0.5;
    let left = (a.x.min(b.x) - padding).floor().max(0.0) as usize;
    let top = (a.y.min(b.y) - padding).floor().max(0.0) as usize;
    let right = ((a.x.max(b.x) + padding).ceil() as usize).min(frame.width - 1);
    let bottom = ((a.y.max(b.y) + padding).ceil() as usize).min(frame.height - 1);
    let dx = b.x - a.x;
    let dy = b.y - a.y;
    let squared = dx * dx + dy * dy;
    for row in top..=bottom {
        for column in left..=right {
            let x = column as f64;
            let y = row as f64;
            let t = if squared > 0.0 {
                ((x - a.x) * dx + (y - a.y) * dy) / squared
            } else {
                0.0
            }
            .clamp(0.0, 1.0);
            let radius = style_a.0 + (style_b.0 - style_a.0) * t;
            let opacity = style_a.1 + (style_b.1 - style_a.1) * t;
            let distance = (x - a.x - dx * t).hypot(y - a.y - dy * t);
            let solid = (radius + 0.5 - distance).clamp(0.0, 1.0);
            let halo = 0.42
                * ((radius * 2.4 - distance) / (radius * 1.4))
                    .clamp(0.0, 1.0)
                    .powi(2);
            // Only the current laser spot has a hot white core. Whitening
            // every trail segment made fast loops look like several spots.
            pixel(frame, column, row, solid.max(halo) * opacity, color);
        }
    }
}
fn point(frame: &mut Frame, at: Point, scale: f64, color: u32) {
    let x = at.x - f64::from(frame.left);
    let y = at.y - f64::from(frame.top);
    let glow = 10.0 * scale;
    let left = (x - glow).floor().max(0.0) as usize;
    let top = (y - glow).floor().max(0.0) as usize;
    let right = ((x + glow).ceil() as usize).min(frame.width - 1);
    let bottom = ((y + glow).ceil() as usize).min(frame.height - 1);
    for row in top..=bottom {
        for column in left..=right {
            let distance = (column as f64 - x).hypot(row as f64 - y);
            // Keep the chosen B spot's bright body, but smoothly extinguish
            // its long, low-opacity Gaussian skirt. That skirt washed out
            // against the background and looked like a dusty grey border.
            let d = distance / scale;
            let solid = if d <= 1.2 {
                1.0
            } else {
                (-((d - 1.2) / 4.0).powi(2)).exp()
            };
            let edge = ((d - 4.5) / 3.0).clamp(0.0, 1.0);
            let opacity = solid * (1.0 - edge * edge * (3.0 - 2.0 * edge));
            let t = ((d - 0.2) / 1.5).clamp(0.0, 1.0);
            let light = 1.0 - t * t * (3.0 - 2.0 * t);
            let alpha = (opacity * 255.0).round() as u32;
            let color = bright_color(color, light);
            let value = &mut frame.pixels[row * frame.width + column];
            let blend = |shift: u32| {
                (((color >> shift) & 255) * alpha + ((*value >> shift) & 255) * (255 - alpha) + 127)
                    / 255
            };
            *value = ((alpha + ((*value >> 24) * (255 - alpha) + 127) / 255) << 24)
                | (blend(16) << 16)
                | (blend(8) << 8)
                | blend(0);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn alpha_at(frame: &Frame, x: i32, y: i32) -> u32 {
        frame.pixels[(y - frame.top) as usize * frame.width + (x - frame.left) as usize] >> 24
    }
    #[test]
    fn small_bright_core_blooms_smoothly_into_saturated_color() {
        let mut trail = Trail::new(LaserSettings {
            trail: false,
            size_percent: 100,
            ..LaserSettings::default()
        });
        let now = Instant::now();
        trail.update(Position { x: 0, y: 0 }, 96, now);
        let frame = trail.frame(now);
        assert!(frame.pixels.iter().filter(|p| **p >> 24 == 255).count() >= 5);
        assert!(alpha_at(&frame, 3, 0) > 150 && alpha_at(&frame, 5, 0) < 150);
        assert_eq!(frame.pixels[10 * frame.width + 10], 0xffffffff);
        let color = frame.pixels[10 * frame.width + 15];
        assert_eq!((color >> 16) & 255, color >> 24);
        assert!(((color >> 8) & 255) < 50);
        assert!(alpha_at(&frame, 6, 0) > 20 && alpha_at(&frame, 6, 0) < 40);
        assert!(alpha_at(&frame, 7, 0) <= 3);
        for x in 2..10 {
            assert!(alpha_at(&frame, x, 0) <= alpha_at(&frame, x - 1, 0));
        }
        assert_eq!(alpha_at(&frame, 10, 0), 0);
        assert!(frame.pixels.iter().any(|p| p >> 24 > 0 && p >> 24 < 255));
        for p in frame.pixels {
            let a = p >> 24;
            assert!((p & 255) <= a && ((p >> 8) & 255) <= a && ((p >> 16) & 255) <= a);
        }
    }
    #[test]
    fn spot_has_a_compact_colored_edge_without_dark_matte_pixels() {
        let now = Instant::now();
        for color in [0xff3030, 0xff9020, 0xffe030, 0x30dd70, 0x30a0ff, 0xc060ff] {
            for dpi in [96, 144, 288] {
                for size_percent in [50, 100, 110, 200, 300] {
                    let mut trail = Trail::new(LaserSettings {
                        color,
                        trail: false,
                        size_percent,
                        ..LaserSettings::default()
                    });
                    trail.update(Position { x: 0, y: 0 }, dpi, now);
                    let frame = trail.frame(now);
                    let scale = (f64::from(dpi) / 96.0 * f64::from(size_percent) / 100.0).min(12.7);
                    let mut old_faint = 0;
                    let mut new_faint = 0;
                    let mut body = 0;
                    for row in 0..frame.height {
                        for column in 0..frame.width {
                            let d = ((column as i32 + frame.left) as f64)
                                .hypot((row as i32 + frame.top) as f64)
                                / scale;
                            let pixel = frame.pixels[row * frame.width + column];
                            let alpha = pixel >> 24;
                            if d >= 7.5 {
                                assert_eq!(pixel, 0, "light persisted outside the compact edge");
                            }
                            let old_alpha = (-((d - 1.2).max(0.0) / 4.0).powi(2)).exp();
                            old_faint += usize::from((0.02..0.25).contains(&old_alpha));
                            new_faint += usize::from(alpha > 0 && alpha < 64);
                            body += usize::from(alpha >= 128);
                            for shift in [0, 8, 16] {
                                let channel = (pixel >> shift) & 255;
                                assert!(channel <= alpha);
                                // Unpremultiplying must retain the selected hue;
                                // the hot core may only brighten its channels.
                                assert!(channel * 255 + 128 >= ((color >> shift) & 255) * alpha);
                                if (color >> shift) & 255 == 255 {
                                    assert_eq!(channel, alpha);
                                }
                                // Source-over against both backgrounds cannot
                                // introduce a black/grey matte around the light.
                                for background in [24, 248] {
                                    let composited =
                                        channel + (background * (255 - alpha) + 127) / 255;
                                    assert!(
                                        composited + 1 >= background.min((color >> shift) & 255)
                                    );
                                }
                            }
                        }
                    }
                    assert!(body > 0);
                    assert!(
                        new_faint * 10 < old_faint * 6,
                        "faint skirt remained too wide"
                    );
                }
            }
        }
    }
    #[test]
    fn size_and_dpi_scale_point_without_moving_hotspot_or_exceeding_budget() {
        let now = Instant::now();
        for dpi in [96, 144, 192, 288, 480] {
            for size in [50, 100, 110, 200, 300] {
                let mut trail = Trail::new(LaserSettings {
                    size_percent: size,
                    ..LaserSettings::default()
                });
                let position = Position { x: -100, y: 40 };
                trail.update(position, dpi, now);
                let frame = trail.frame(now);
                assert!(frame.width <= MAX_SIDE && frame.height <= MAX_SIDE);
                assert_eq!(alpha_at(&frame, position.x, position.y), 255);
                let radius = (10.0 * (f64::from(size) / 100.0 * f64::from(dpi) / 96.0).min(12.7))
                    .ceil() as usize;
                assert_eq!(
                    (frame.width, frame.height),
                    (2 * radius + 1, 2 * radius + 1)
                );
                trail.update(
                    Position {
                        x: 10000,
                        y: -10000,
                    },
                    dpi,
                    now + Duration::from_millis(20),
                );
                let moving = trail.frame(now + Duration::from_millis(20));
                assert!(moving.width <= MAX_SIDE && moving.height <= MAX_SIDE);
                assert_eq!(alpha_at(&moving, 10000, -10000), 255);
            }
        }
    }
    #[test]
    fn continuous_tail_tapers_and_fades_but_point_stays_opaque() {
        let mut trail = Trail::new(LaserSettings {
            size_percent: 100,
            ..LaserSettings::default()
        });
        let now = Instant::now();
        for index in 0..6 {
            trail.update(
                Position {
                    x: index * 20,
                    y: 0,
                },
                96,
                now + Duration::from_millis(index as u64 * 20),
            );
        }
        let before = trail.frame(now + Duration::from_millis(100));
        // Every interior center pixel is connected; the far end is thinner
        // and more transparent than the section close to the point.
        for x in (before.left + 20)..94 {
            assert!(alpha_at(&before, x, 0) > 0);
        }
        assert!(alpha_at(&before, before.left + 15, 0) < alpha_at(&before, 80, 0));
        assert!(alpha_at(&before, before.left + 15, 1) < alpha_at(&before, 80, 1));
        trail.update(
            Position { x: 100, y: 0 },
            96,
            now + Duration::from_millis(350),
        );
        assert!(
            alpha_at(&trail.frame(now + Duration::from_millis(350)), 80, 0)
                < alpha_at(&before, 80, 0)
        );
        trail.update(Position { x: 100, y: 0 }, 96, now + Duration::from_secs(1));
        let after = trail.frame(now + Duration::from_secs(1));
        assert_eq!((after.width, after.height), (21, 21));
        assert_eq!(alpha_at(&after, 100, 0), 255);
        assert!(!trail.update(Position { x: 100, y: 0 }, 96, now + Duration::from_secs(2)));
    }
    #[test]
    fn speed_controls_length_smoothly_and_stop_uses_age_fade() {
        let now = Instant::now();
        let simulate = |step: i32| {
            let mut trail = Trail::new(LaserSettings {
                fade_ms: 3000,
                ..LaserSettings::default()
            });
            for index in 0..101 {
                trail.update(
                    Position {
                        x: index * step,
                        y: -50,
                    },
                    96,
                    now + Duration::from_millis(index as u64 * 8),
                );
            }
            trail
        };
        let slow = simulate(1);
        let mut fast = simulate(10);
        assert!(
            fast.frame(now + Duration::from_millis(800)).width
                > slow.frame(now + Duration::from_millis(800)).width * 2
        );
        let previous = fast.frame(now + Duration::from_millis(800)).width;
        fast.update(
            Position { x: 1001, y: -50 },
            96,
            now + Duration::from_millis(808),
        );
        assert!(
            fast.frame(now + Duration::from_millis(808))
                .width
                .abs_diff(previous)
                < 12
        );
        let stopped = fast.moved_at;
        fast.update(
            Position { x: 1001, y: -50 },
            96,
            now + Duration::from_millis(1200),
        );
        assert_eq!(fast.moved_at, stopped);
        let frame = fast.frame(now + Duration::from_millis(1200));
        assert_eq!(alpha_at(&frame, 1001, -50), 255);
        fast.update(
            Position { x: 1001, y: -50 },
            96,
            now + Duration::from_secs(4),
        );
        assert_eq!(
            fast.frame(now + Duration::from_secs(4)).width,
            (2 * fast.radius() + 1) as usize
        );
    }
    #[test]
    fn temporal_length_is_report_rate_independent_and_resume_has_no_old_bridge() {
        let now = Instant::now();
        let simulate = |interval: u64| {
            let mut trail = Trail::new(LaserSettings::default());
            for t in (0..=400).step_by(interval as usize) {
                trail.update(
                    Position { x: t as i32, y: 0 },
                    96,
                    now + Duration::from_millis(t),
                );
            }
            trail
        };
        let mut fine = simulate(8);
        let coarse = simulate(16);
        assert_eq!(
            fine.frame(now + Duration::from_millis(400)).width,
            coarse.frame(now + Duration::from_millis(400)).width
        );
        fine.update(
            Position { x: 400, y: 0 },
            96,
            now + Duration::from_millis(800),
        );
        fine.update(
            Position { x: 408, y: 0 },
            96,
            now + Duration::from_millis(808),
        );
        let frame = fine.frame(now + Duration::from_millis(808));
        assert!(
            frame.width <= (2 * fine.radius() + 10) as usize,
            "resuming revived old fast-movement history"
        );
        assert_eq!(alpha_at(&frame, 408, 0), 255);
    }
    #[test]
    fn length_boundary_is_interpolated_and_turns_stay_inside_sample_bounds() {
        let mut trail = Trail::new(LaserSettings {
            trail_length_px: 120,
            ..LaserSettings::default()
        });
        let now = Instant::now();
        trail.update(Position { x: 0, y: 0 }, 96, now);
        trail.update(
            Position { x: 200, y: 0 },
            96,
            now + Duration::from_millis(20),
        );
        let path = trail.path(now + Duration::from_millis(20));
        assert!((path.first().unwrap().point.x - 80.0).abs() < 0.001);
        assert_eq!(path.last().unwrap().point.x, 200.0);
        trail.update(
            Position { x: 200, y: 40 },
            96,
            now + Duration::from_millis(40),
        );
        let path = trail.path(now + Duration::from_millis(40));
        assert!(
            path.iter()
                .all(|v| v.point.x <= 200.0 && v.point.y >= 0.0 && v.point.y <= 40.0)
        );
        assert!(path.iter().any(|v| v.point.x < 200.0 && v.point.y > 0.0));
        assert!(
            path.windows(2)
                .all(|p| p[0].point.distance(p[1].point) <= 1.01)
        );
    }
    #[test]
    fn maximum_trail_length_scales_with_dpi_and_updates_without_clearing_the_head() {
        let now = Instant::now();
        for dpi in [96, 144, 288] {
            for size_percent in [50, 100, 110, 200, 300] {
                for trail_length_px in [20, 120, 180, 200, 300] {
                    let mut trail = Trail::new(LaserSettings {
                        size_percent,
                        trail_length_px,
                        ..LaserSettings::default()
                    });
                    for index in 0..20 {
                        trail.update(
                            Position {
                                x: -900 + index * (dpi as i32 / 4),
                                y: -30,
                            },
                            dpi,
                            now + Duration::from_millis(index as u64 * 8),
                        );
                    }
                    let head = trail.position.unwrap();
                    let frame = trail.frame(now + Duration::from_millis(152));
                    let maximum = trail.maximum_length();
                    assert!(maximum <= f64::from(trail_length_px) * f64::from(dpi) / 96.0);
                    assert!(
                        frame.width <= maximum.ceil() as usize + 2 * trail.radius() as usize + 2
                    );
                    assert!(frame.width <= MAX_SIDE && frame.height <= MAX_SIDE);
                    assert_eq!(alpha_at(&frame, head.x, head.y), 255);
                }
            }
        }
        let mut trail = Trail::new(LaserSettings::default());
        trail.update(Position { x: 0, y: 0 }, 96, now);
        trail.update(
            Position { x: 200, y: 0 },
            96,
            now + Duration::from_millis(8),
        );
        trail.configure(LaserSettings {
            trail_length_px: 20,
            ..LaserSettings::default()
        });
        assert!((trail.path(now + Duration::from_millis(8))[0].point.x - 180.0).abs() < 0.001);
        assert_eq!(trail.position, Some(Position { x: 200, y: 0 }));
        assert_eq!(
            trail.frame(now + Duration::from_millis(8)).width,
            (20 + 2 * trail.radius() + 1) as usize
        );
        // Increasing the limit cannot resurrect history already discarded.
        trail.configure(LaserSettings {
            trail_length_px: 200,
            ..LaserSettings::default()
        });
        assert!((trail.path(now + Duration::from_millis(8))[0].point.x - 180.0).abs() < 0.001);
    }
    #[test]
    fn fast_loops_have_one_hot_spot_and_a_colored_trail() {
        let now = Instant::now();
        for color in [0xff3030, 0xffe030, 0x30dd70, 0x30a0ff, 0xc060ff] {
            let mut trail = Trail::new(LaserSettings {
                color,
                trail_length_px: 200,
                ..LaserSettings::default()
            });
            for index in 0..100 {
                let angle = index as f64 * 0.65;
                let head = Position {
                    x: -100 + (angle.cos() * 60.0).round() as i32,
                    y: -40 + (angle.sin() * 60.0).round() as i32,
                };
                let at = now + Duration::from_millis(index * 8);
                trail.update(head, 144, at);
                let frame = trail.frame(at);
                let mut hot_pixels = 0;
                for row in 0..frame.height {
                    for column in 0..frame.width {
                        let pixel = frame.pixels[row * frame.width + column];
                        let alpha = pixel >> 24;
                        let least = [pixel & 255, (pixel >> 8) & 255, (pixel >> 16) & 255]
                            .into_iter()
                            .min()
                            .unwrap();
                        if alpha > 16 && least * 255 > alpha * 185 {
                            hot_pixels += 1;
                            let distance = (column as f64 + f64::from(frame.left - head.x))
                                .hypot(row as f64 + f64::from(frame.top - head.y));
                            assert!(
                                distance <= 1.7 * 1.5,
                                "an old trail position looked like another hot spot"
                            );
                        }
                    }
                }
                assert!(hot_pixels > 0);
                assert!(frame.width <= MAX_SIDE && frame.height <= MAX_SIDE);
                assert_eq!(alpha_at(&frame, head.x, head.y), 255);
            }
        }
    }
    #[test]
    fn short_fast_circular_tail_keeps_the_turn_before_the_visible_boundary() {
        let now = Instant::now();
        let mut trail = Trail::new(LaserSettings::default());
        for index in 0..4 {
            let angle = (index as f64 - 3.0) * 0.6;
            trail.update(
                Position {
                    x: -500 + (300.0 * angle.cos()).round() as i32,
                    y: -600 + (300.0 * angle.sin()).round() as i32,
                },
                96,
                now + Duration::from_millis(index * 16),
            );
        }
        // Consecutive endpoints are farther apart than the visible tail.
        // Two points alone discard the turn and can only draw a straight chord.
        assert!(trail.samples.len() >= 3, "length clipping erased the turn");
        let frame = trail.frame(now + Duration::from_millis(48));
        assert!(frame.width <= MAX_SIDE && frame.height <= MAX_SIDE);
        assert_eq!(alpha_at(&frame, -200, -600), 255);
        // Away from the head, a curved tail must bow towards the circle,
        // instead of staying on the last straight endpoint-to-endpoint chord.
        let previous = trail.samples[trail.samples.len() - 2].point;
        let head = Point::from(trail.position.unwrap());
        let chord = previous.distance(head);
        let mut curved_pixels = 0;
        for row in 0..frame.height {
            for column in 0..frame.width {
                let x = column as f64 + f64::from(frame.left);
                let y = row as f64 + f64::from(frame.top);
                if frame.pixels[row * frame.width + column] >> 24 > 100
                    && (x - head.x).hypot(y - head.y) > 20.0
                {
                    let cross = (x - previous.x) * (head.y - previous.y)
                        - (y - previous.y) * (head.x - previous.x);
                    if cross / chord > 3.0 {
                        curved_pixels += 1;
                    }
                }
            }
        }
        assert!(
            curved_pixels > 10,
            "the short circular tail remained a chord"
        );
    }
    #[test]
    fn curve_context_is_bounded_and_sharp_reversals_do_not_predict_the_hotspot() {
        let now = Instant::now();
        for dpi in [96, 144, 288] {
            for trail_length_px in [20, 120, 180, 200, 300] {
                let mut trail = Trail::new(LaserSettings {
                    trail_length_px,
                    ..LaserSettings::default()
                });
                for index in 0..600 {
                    // Alternate long jumps and close points with irregular
                    // delivery intervals; preserve the real endpoint exactly.
                    let position = Position {
                        x: -10_000 + if index % 4 < 2 { 0 } else { 20_000 } + index % 2,
                        y: -500 + index % 3,
                    };
                    let at = now + Duration::from_micros(index as u64 * 100);
                    trail.update(position, dpi, at);
                    let path = trail.path(at);
                    assert!(trail.samples.len() <= MAX_SAMPLES);
                    assert!(path.len() <= (trail.maximum_length() / 0.75).ceil() as usize + 3);
                    let head = path.last().unwrap().point;
                    assert_eq!(
                        (head.x, head.y),
                        (f64::from(position.x), f64::from(position.y))
                    );
                    let length: f64 = path
                        .windows(2)
                        .map(|p| p[0].point.distance(p[1].point))
                        .sum();
                    assert!(length <= trail.maximum_length() + 0.001);
                    let frame = trail.frame(at);
                    assert!(frame.width <= MAX_SIDE && frame.height <= MAX_SIDE);
                    assert_eq!(alpha_at(&frame, position.x, position.y), 255);
                }
                trail.update(trail.position.unwrap(), dpi, now + Duration::from_secs(2));
                let frame = trail.frame(now + Duration::from_secs(2));
                assert_eq!(frame.width, (2 * trail.radius() + 1) as usize);
                assert!(trail.samples.is_empty());
            }
        }
    }
    #[test]
    fn size_color_and_trail_changes_clear_history_while_fade_change_keeps_path() {
        let mut settings = LaserSettings::default();
        let mut trail = Trail::new(settings);
        let now = Instant::now();
        trail.update(Position { x: 0, y: 0 }, 144, now);
        trail.update(
            Position { x: 60, y: 0 },
            144,
            now + Duration::from_millis(20),
        );
        settings.fade_ms = 1700;
        trail.configure(settings);
        assert_eq!(trail.samples.len(), 2);
        settings.size_percent = 200;
        settings.color = 0x00ff00;
        trail.configure(settings);
        trail.update(
            Position { x: 60, y: 0 },
            144,
            now + Duration::from_millis(30),
        );
        let frame = trail.frame(now + Duration::from_millis(30));
        assert_eq!((frame.width, frame.height), (61, 61));
        assert_eq!(frame.pixels[30 * 61 + 30], 0xffffffff);
        let colored = frame.pixels[30 * 61 + 45];
        assert_eq!((colored >> 8) & 255, colored >> 24);
        assert_eq!(colored & 0xff00ff, 0);
        settings.trail = false;
        trail.configure(settings);
        trail.update(
            Position { x: 200, y: 0 },
            144,
            now + Duration::from_millis(40),
        );
        assert_eq!(trail.frame(now + Duration::from_millis(40)).width, 61);
    }
}
