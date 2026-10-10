//! Export actual cursor pixels on light/dark backgrounds without a screenshot.
use phone_mouse_server::{
    laser_trail::{Position, Trail},
    protocol::LaserSettings,
};
use std::time::{Duration, Instant};
fn main() -> anyhow::Result<()> {
    let mut image = image::RgbaImage::new(760, 660);
    let colors = [0xff3030, 0xff9020, 0xffe030, 0x30dd70, 0x30a0ff, 0xc060ff];
    for (row, color) in colors.into_iter().enumerate() {
        for column in 0..2 {
            let bg = if column == 0 {
                [248, 249, 251]
            } else {
                [24, 29, 36]
            };
            for y in 0..110 {
                for x in 0..380 {
                    image.put_pixel(
                        column * 380 + x,
                        row as u32 * 110 + y,
                        image::Rgba([bg[0], bg[1], bg[2], 255]),
                    );
                }
            }
            for (speed_index, step) in [1, 8].into_iter().enumerate() {
                let mut trail = Trail::new(LaserSettings {
                    color,
                    ..LaserSettings::default()
                });
                let start = Instant::now();
                let end = start + Duration::from_millis(400);
                for n in 0..=50 {
                    trail.update(
                        Position {
                            x: n * step,
                            y: (12.0 * (f64::from(n) * 0.09).sin()).round() as i32,
                        },
                        144,
                        start + Duration::from_millis(n as u64 * 8),
                    );
                }
                let frame = trail.frame(end);
                let head = Position {
                    x: 50 * step,
                    y: (12.0 * (50.0_f64 * 0.09).sin()).round() as i32,
                };
                let origin_x =
                    column as i32 * 380 + [110, 340][speed_index] - (head.x - frame.left);
                let origin_y = row as i32 * 110 + 55 - (head.y - frame.top);
                for y in 0..frame.height {
                    for x in 0..frame.width {
                        let px = origin_x + x as i32;
                        let py = origin_y + y as i32;
                        if px < column as i32 * 380
                            || px >= (column as i32 + 1) * 380
                            || py < row as i32 * 110
                            || py >= (row as i32 + 1) * 110
                        {
                            continue;
                        }
                        let p = frame.pixels[y * frame.width + x];
                        let alpha = p >> 24;
                        let blend = |shift: u32, b: u8| {
                            (((p >> shift) & 255) + u32::from(b) * (255 - alpha) / 255) as u8
                        };
                        image.put_pixel(
                            px as u32,
                            py as u32,
                            image::Rgba([blend(16, bg[0]), blend(8, bg[1]), blend(0, bg[2]), 255]),
                        );
                    }
                }
            }
        }
    }
    let path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "../artifacts/laser-neon-preview.png".into());
    image.save(&path)?;
    println!("{path}");
    Ok(())
}
