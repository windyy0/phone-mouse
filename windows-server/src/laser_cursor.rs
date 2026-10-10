//! The entire laser point and fading trail use the Windows cursor compositor.
//! A separate process restores the user's cursor theme even after a crash.
use crate::{
    laser_trail::{Frame, Position, Trail},
    protocol::LaserSettings,
};
use anyhow::{Context, Result, bail};
use std::{
    io::{BufRead, BufReader, Read, Write},
    os::windows::process::CommandExt,
    process::{Child, Command, Stdio},
    ptr::null_mut,
    sync::mpsc,
    time::{Duration, Instant},
};
use windows_sys::Win32::{Graphics::Gdi::*, UI::WindowsAndMessaging::*};

const CURSORS: &[u32] = &[
    32512, 32513, 32514, 32515, 32516, 32642, 32643, 32644, 32645, 32646, 32648, 32649, 32650,
    32651, 32671, 32672,
];

pub fn restore_cursors() -> Result<()> {
    if unsafe { SystemParametersInfoW(SPI_SETCURSORS, 0, null_mut(), 0) } == 0 {
        bail!("恢复系统鼠标失败：{}", std::io::Error::last_os_error());
    }
    Ok(())
}

/// The pipe closes even when the parent is terminated. This helper never opens
/// a window and never changes the user's persisted cursor theme.
pub fn run_cursor_guardian() -> Result<()> {
    println!("ready");
    std::io::stdout().flush()?;
    let mut buffer = [0; 32];
    while std::io::stdin().read(&mut buffer)? != 0 {}
    restore_cursors()
}

pub(super) struct CursorGuard {
    child: Child,
    installed: Option<(u32, u32, u32)>,
    point: Option<CursorImage>,
    roles: Vec<(u32, HCURSOR)>,
    active_role: Option<u32>,
    trail_installed: bool,
}
impl CursorGuard {
    pub(super) fn start(settings: LaserSettings, dpi: u32) -> Result<Self> {
        let mut child = Command::new(std::env::current_exe()?)
            .arg("--cursor-guardian")
            .creation_flags(0x08000000)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .context("启动鼠标恢复守护失败")?;
        let output = child.stdout.take().context("读取恢复守护状态失败")?;
        let (sender, receiver) = mpsc::sync_channel(1);
        std::thread::spawn(move || {
            let mut line = String::new();
            let result = BufReader::new(output)
                .read_line(&mut line)
                .map(|_| line.trim() == "ready");
            let _ = sender.send(result);
        });
        if !matches!(receiver.recv_timeout(Duration::from_secs(2)), Ok(Ok(true))) {
            let _ = child.kill();
            let _ = child.wait();
            bail!("鼠标恢复守护未就绪");
        }
        let mut guard = Self {
            child,
            installed: None,
            point: None,
            roles: Vec::new(),
            active_role: None,
            trail_installed: false,
        };
        guard.configure(settings, dpi)?;
        Ok(guard)
    }
    pub(super) fn healthy(&mut self) -> Result<bool> {
        Ok(self.child.try_wait()?.is_none())
    }
    pub(super) fn configure(&mut self, settings: LaserSettings, dpi: u32) -> Result<()> {
        let key = (settings.color, settings.size_percent, dpi.max(96));
        if self.installed == Some(key) {
            return Ok(());
        }
        let image = CursorImage::new(settings, key.2)?;
        let mut roles = Vec::new();
        for &id in CURSORS {
            let cursor = image.create()?;
            if unsafe { SetSystemCursor(cursor, id) } == 0 {
                unsafe {
                    DestroyCursor(cursor);
                }
                if id >= 32671 {
                    continue;
                }
                bail!("替换激光鼠标失败：{}", std::io::Error::last_os_error());
            }
            roles.push((id, unsafe {
                LoadCursorW(null_mut(), id as usize as *const u16)
            }));
        }
        self.point = Some(image);
        self.roles = roles;
        self.active_role = None;
        self.trail_installed = false;
        self.installed = Some(key);
        Ok(())
    }
    pub(super) fn current_role(&self) -> Result<Option<u32>> {
        let mut info: CURSORINFO = unsafe { std::mem::zeroed() };
        info.cbSize = std::mem::size_of::<CURSORINFO>() as u32;
        if unsafe { GetCursorInfo(&mut info) } == 0 {
            bail!("读取鼠标显示状态失败");
        }
        Ok(self
            .roles
            .iter()
            .find(|(_, cursor)| *cursor == info.hCursor)
            .map(|(id, _)| *id))
    }
    pub(super) fn role_changed(&self, role: Option<u32>) -> bool {
        role != self.active_role
    }
    pub(super) fn prepare_move(&mut self) -> Result<bool> {
        if !self.trail_installed {
            return Ok(false);
        }
        // Cursor position and image are separate Windows operations. Moving
        // with the previous image briefly translates all historical strokes,
        // even if the correct image is installed before our request returns.
        // Keep the spot visible, but remove the old trail before the OS move.
        let mut staged = false;
        if let Some(role) = self.current_role()? {
            if Some(role) == self.active_role {
                let cursor = self.point.as_ref().unwrap().create()?;
                self.replace(role, cursor)?;
                staged = true;
            }
        }
        self.trail_installed = false;
        Ok(staged)
    }
    pub(super) fn paint(
        &mut self,
        role: Option<u32>,
        frame: &Frame,
        position: Position,
    ) -> Result<()> {
        if role != self.active_role {
            if let Some(previous) = self.active_role {
                // Inactive shapes keep only a point, so re-entering a cursor
                // role never briefly resurrects an old trail from that role.
                let cursor = self.point.as_ref().unwrap().create()?;
                self.replace(previous, cursor)?;
            }
        }
        if let Some(id) = role {
            let image = CursorImage::from_frame(frame, position)?;
            self.replace(id, image.create()?)?;
        }
        self.active_role = role;
        let point = self.point.as_ref().unwrap();
        self.trail_installed = role.is_some()
            && (frame.width > (2 * point.hotspot.0 + 1) as usize
                || frame.height > (2 * point.hotspot.1 + 1) as usize);
        Ok(())
    }
    fn replace(&mut self, id: u32, cursor: HCURSOR) -> Result<()> {
        // Transfer one independent handle to Windows. Only the currently
        // displayed standard role is updated per frame, not all 16 roles.
        if unsafe { SetSystemCursor(cursor, id) } == 0 {
            unsafe {
                DestroyCursor(cursor);
            }
            bail!("更新激光鼠标失败：{}", std::io::Error::last_os_error());
        }
        if let Some((_, handle)) = self.roles.iter_mut().find(|(role, _)| *role == id) {
            *handle = unsafe { LoadCursorW(null_mut(), id as usize as *const u16) };
        }
        Ok(())
    }
    pub(super) fn invalidate(&mut self) {
        self.installed = None;
    }
}
impl Drop for CursorGuard {
    fn drop(&mut self) {
        self.child.stdin.take();
        // Wait for restoration before acknowledging that laser mode is off.
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            match self.child.try_wait() {
                Ok(Some(status)) if status.success() => return,
                Ok(None) if Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(10));
                }
                _ => break,
            }
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
        let _ = restore_cursors();
    }
}

struct OwnedBitmap(HBITMAP);
impl Drop for OwnedBitmap {
    fn drop(&mut self) {
        unsafe {
            DeleteObject(self.0);
        }
    }
}
#[repr(C)]
struct MaskInfo {
    header: BITMAPINFOHEADER,
    colors: [RGBQUAD; 2],
}
struct CursorImage {
    color: OwnedBitmap,
    mask: OwnedBitmap,
    hotspot: (u32, u32),
}
fn native_pixel(premultiplied: u32) -> u32 {
    let alpha = premultiplied >> 24;
    if alpha == 0 {
        return 0;
    }
    // Frame uses premultiplied source-over pixels. CreateIconIndirect's
    // color DIB takes straight RGB: Windows premultiplies it internally.
    // Passing Frame's RGB directly applied alpha twice, producing grey/dark
    // fringes. The DrawIconEx regression exercises the actual Win32 boundary.
    let channel =
        |shift: u32| ((((premultiplied >> shift) & 255) * 255 + alpha / 2) / alpha).min(255);
    (alpha << 24) | (channel(16) << 16) | (channel(8) << 8) | channel(0)
}
impl CursorImage {
    fn new(settings: LaserSettings, dpi: u32) -> Result<Self> {
        // Use the same rasterizer as the trail. The hotspot is
        // exactly at the point's center, including odd pixel sizes at 150% DPI.
        let now = Instant::now();
        let mut point = Trail::new(LaserSettings {
            trail: false,
            ..settings
        });
        point.update(Position { x: 0, y: 0 }, dpi, now);
        Self::from_frame(&point.frame(now), Position { x: 0, y: 0 })
    }
    fn from_frame(frame: &Frame, position: Position) -> Result<Self> {
        let hotspot_x = i64::from(position.x) - i64::from(frame.left);
        let hotspot_y = i64::from(position.y) - i64::from(frame.top);
        if hotspot_x < 0
            || hotspot_y < 0
            || hotspot_x >= frame.width as i64
            || hotspot_y >= frame.height as i64
        {
            bail!("激光鼠标热点超出位图边界");
        }
        let mut info: BITMAPINFO = unsafe { std::mem::zeroed() };
        info.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
        info.bmiHeader.biWidth = frame.width as i32;
        info.bmiHeader.biHeight = -(frame.height as i32);
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = BI_RGB;
        let mut bits = null_mut();
        let bitmap = unsafe {
            CreateDIBSection(null_mut(), &info, DIB_RGB_COLORS, &mut bits, null_mut(), 0)
        };
        if bitmap.is_null() {
            bail!("创建激光鼠标位图失败");
        }
        let color = OwnedBitmap(bitmap);
        let destination =
            unsafe { std::slice::from_raw_parts_mut(bits.cast::<u32>(), frame.pixels.len()) };
        for (destination, &source) in destination.iter_mut().zip(&frame.pixels) {
            *destination = native_pixel(source);
        }
        let mut mask_info = MaskInfo {
            header: info.bmiHeader,
            colors: [
                unsafe { std::mem::zeroed() },
                RGBQUAD {
                    rgbBlue: 255,
                    rgbGreen: 255,
                    rgbRed: 255,
                    rgbReserved: 0,
                },
            ],
        };
        mask_info.header.biBitCount = 1;
        let mut mask_bits = null_mut();
        let bitmap = unsafe {
            CreateDIBSection(
                null_mut(),
                (&mask_info as *const MaskInfo).cast(),
                DIB_RGB_COLORS,
                &mut mask_bits,
                null_mut(),
                0,
            )
        };
        if bitmap.is_null() {
            bail!("创建激光鼠标掩码失败");
        }
        let mask = OwnedBitmap(bitmap);
        let stride = frame.width.div_ceil(32) * 4;
        let bytes = unsafe {
            std::slice::from_raw_parts_mut(mask_bits.cast::<u8>(), stride * frame.height)
        };
        bytes.fill(0);
        for row in 0..frame.height {
            for column in 0..frame.width {
                if frame.pixels[row * frame.width + column] >> 24 == 0 {
                    bytes[row * stride + column / 8] |= 0x80 >> (column % 8);
                }
            }
        }
        // Neither bitmap is selected into a DC: CreateIconIndirect copies them.
        Ok(Self {
            color,
            mask,
            hotspot: (hotspot_x as u32, hotspot_y as u32),
        })
    }
    fn create(&self) -> Result<HCURSOR> {
        let info = ICONINFO {
            fIcon: 0,
            xHotspot: self.hotspot.0,
            yHotspot: self.hotspot.1,
            hbmMask: self.mask.0,
            hbmColor: self.color.0,
        };
        let cursor = unsafe { CreateIconIndirect(&info) };
        if cursor.is_null() {
            bail!("创建激光鼠标失败：{}", std::io::Error::last_os_error());
        }
        Ok(cursor)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn win32_composition_applies_alpha_once_on_light_and_dark_backgrounds() {
        for color in [
            0xff3030_u32,
            0xff9020,
            0xffe030,
            0x30dd70,
            0x30a0ff,
            0xc060ff,
        ] {
            for alpha in [0_u32, 1, 5, 64, 128, 192, 254, 255] {
                let premultiply = |shift: u32| (((color >> shift) & 255) * alpha + 127) / 255;
                let source = (alpha << 24)
                    | (premultiply(16) << 16)
                    | (premultiply(8) << 8)
                    | premultiply(0);
                let image = CursorImage::from_frame(
                    &Frame {
                        left: 0,
                        top: 0,
                        width: 1,
                        height: 1,
                        pixels: vec![source],
                    },
                    Position { x: 0, y: 0 },
                )
                .unwrap();
                let cursor = image.create().unwrap();
                for background in [0x00f8f9fb_u32, 0x00181d24] {
                    let mut info: BITMAPINFO = unsafe { std::mem::zeroed() };
                    info.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
                    info.bmiHeader.biWidth = 1;
                    info.bmiHeader.biHeight = -1;
                    info.bmiHeader.biPlanes = 1;
                    info.bmiHeader.biBitCount = 32;
                    info.bmiHeader.biCompression = BI_RGB;
                    let mut bits = null_mut();
                    let bitmap = OwnedBitmap(unsafe {
                        CreateDIBSection(
                            null_mut(),
                            &info,
                            DIB_RGB_COLORS,
                            &mut bits,
                            null_mut(),
                            0,
                        )
                    });
                    assert!(!bitmap.0.is_null());
                    unsafe {
                        bits.cast::<u32>().write(0xff000000 | background);
                    }
                    let dc = unsafe { CreateCompatibleDC(null_mut()) };
                    assert!(!dc.is_null());
                    let previous = unsafe { SelectObject(dc, bitmap.0) };
                    assert_ne!(
                        unsafe { DrawIconEx(dc, 0, 0, cursor, 1, 1, 0, null_mut(), DI_NORMAL) },
                        0
                    );
                    unsafe {
                        GdiFlush();
                    }
                    let actual = unsafe { bits.cast::<u32>().read() };
                    unsafe {
                        SelectObject(dc, previous);
                        DeleteDC(dc);
                    }
                    for shift in [0, 8, 16] {
                        let expected = ((source >> shift) & 255)
                            + (((background >> shift) & 255) * (255 - alpha) + 127) / 255;
                        assert!(
                            ((actual >> shift) & 255).abs_diff(expected) <= 2,
                            "alpha applied twice: color={color:06x} alpha={alpha} bg={background:06x} actual={actual:08x} expected channel={expected}"
                        );
                    }
                }
                unsafe {
                    DestroyCursor(cursor);
                }
            }
        }
    }
}
