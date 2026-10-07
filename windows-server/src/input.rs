use crate::protocol::{Button, Command, Key};
use anyhow::{Result, bail};

pub trait InputSink: Send + Sync {
    fn execute(&self, command: &Command) -> Result<()>;
}

#[derive(Debug, PartialEq)]
pub struct KeyEvent {
    pub vk: u16,
    pub scan: u16,
    pub flags: u32,
}

pub fn text_events(text: &str) -> Vec<KeyEvent> {
    let text = text.replace("\r\n", "\n").replace('\r', "\n");
    let mut events = Vec::with_capacity(text.len() * 2);
    for c in text.chars() {
        let (vk, units, flags) = match c {
            '\n' => (0x0D, vec![0], 0),
            '\t' => (0x09, vec![0], 0),
            _ => (0, c.encode_utf16(&mut [0; 2]).to_vec(), 0x0004),
        };
        for scan in units {
            events.push(KeyEvent { vk, scan, flags });
            events.push(KeyEvent {
                vk,
                scan,
                flags: flags | 0x0002,
            });
        }
    }
    events
}

pub struct WindowsInput;

#[cfg(windows)]
mod native {
    use super::*;
    use std::sync::Mutex;
    use windows_sys::Win32::Foundation::POINT;
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::*;
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetPhysicalCursorPos, SetPhysicalCursorPos};

    static PIXEL_MOVE_LOCK: Mutex<()> = Mutex::new(());

    fn move_pixels(dx: i32, dy: i32) -> Result<()> {
        // Serialize the read/modify/write across UDP and WebSocket workers.
        // Physical coordinates bypass Windows pointer ballistics and DPI scaling;
        // reading the live position also cooperates with an ordinary mouse.
        let _guard = PIXEL_MOVE_LOCK
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let mut current = POINT { x: 0, y: 0 };
        if unsafe { GetPhysicalCursorPos(&mut current) } == 0 {
            bail!("Windows 未能读取鼠标位置。");
        }
        if unsafe {
            SetPhysicalCursorPos(current.x.saturating_add(dx), current.y.saturating_add(dy))
        } == 0
        {
            bail!("Windows 未能移动鼠标，请检查当前桌面权限。");
        }
        Ok(())
    }

    fn send(events: &[INPUT]) -> Result<()> {
        // INPUT's tagged union is built immediately below with matching tags.
        let sent = unsafe {
            SendInput(
                events.len() as u32,
                events.as_ptr(),
                std::mem::size_of::<INPUT>() as i32,
            )
        };
        if sent != events.len() as u32 {
            bail!("Windows 未接收全部输入，请检查目标窗口权限。");
        }
        Ok(())
    }

    fn mouse(data: u32, flags: u32) -> INPUT {
        INPUT {
            r#type: INPUT_MOUSE,
            Anonymous: INPUT_0 {
                mi: MOUSEINPUT {
                    dx: 0,
                    dy: 0,
                    mouseData: data,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        }
    }

    fn keyboard(vk: u16, scan: u16, flags: u32) -> INPUT {
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: vk,
                    wScan: scan,
                    dwFlags: flags,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        }
    }

    fn zoom_inputs(delta: i32, control_held: bool) -> Vec<INPUT> {
        let wheel = mouse(delta as u32, MOUSEEVENTF_WHEEL);
        if control_held {
            vec![wheel]
        } else {
            vec![
                keyboard(VK_LCONTROL, 0, 0),
                wheel,
                keyboard(VK_LCONTROL, 0, KEYEVENTF_KEYUP),
            ]
        }
    }

    fn zoom(delta: i32) -> Result<()> {
        let control_held = unsafe { GetAsyncKeyState(VK_CONTROL as i32) } as u16 & 0x8000 != 0;
        let result = send(&zoom_inputs(delta, control_held));
        // A partial injection must not leave our temporary Ctrl press held.
        if result.is_err() && !control_held {
            let _ = send(&[keyboard(VK_LCONTROL, 0, KEYEVENTF_KEYUP)]);
        }
        result
    }

    impl InputSink for WindowsInput {
        fn execute(&self, command: &Command) -> Result<()> {
            match command {
                Command::MovePixels { dx, dy } => move_pixels(*dx, *dy),
                Command::Scroll { delta } => send(&[mouse(*delta as u32, MOUSEEVENTF_WHEEL)]),
                Command::TouchGesture {
                    horizontal,
                    zoom: delta,
                } => {
                    if *horizontal != 0 {
                        send(&[mouse(*horizontal as u32, MOUSEEVENTF_HWHEEL)])?;
                    }
                    if *delta != 0 {
                        zoom(*delta)?;
                    }
                    Ok(())
                }
                Command::Click { button } => {
                    let (down, up) = match button {
                        Button::Left => (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
                        Button::Right => (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
                        Button::Middle => (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
                    };
                    send(&[mouse(0, down), mouse(0, up)])
                }
                Command::ButtonDown { button } => {
                    let flag = match button {
                        Button::Left => MOUSEEVENTF_LEFTDOWN,
                        Button::Right => MOUSEEVENTF_RIGHTDOWN,
                        Button::Middle => MOUSEEVENTF_MIDDLEDOWN,
                    };
                    send(&[mouse(0, flag)])
                }
                Command::ButtonUp { button } => {
                    let flag = match button {
                        Button::Left => MOUSEEVENTF_LEFTUP,
                        Button::Right => MOUSEEVENTF_RIGHTUP,
                        Button::Middle => MOUSEEVENTF_MIDDLEUP,
                    };
                    send(&[mouse(0, flag)])
                }
                Command::Key { key } => {
                    let vk = match key {
                        Key::Enter => VK_RETURN,
                        Key::Backspace => VK_BACK,
                    };
                    send(&[keyboard(vk, 0, 0), keyboard(vk, 0, KEYEVENTF_KEYUP)])
                }
                Command::Text { text, .. } => {
                    let events: Vec<_> = text_events(text)
                        .into_iter()
                        .map(|e| keyboard(e.vk, e.scan, e.flags))
                        .collect();
                    send(&events)
                }
                _ => Ok(()),
            }
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn zoom_brackets_wheel_with_control_but_preserves_an_existing_hold() {
            let events = zoom_inputs(-120, false);
            assert_eq!(events.len(), 3);
            unsafe {
                assert_eq!(events[0].Anonymous.ki.wVk, VK_LCONTROL);
                assert_eq!(events[0].Anonymous.ki.dwFlags, 0);
                assert_eq!(events[1].Anonymous.mi.mouseData as i32, -120);
                assert_eq!(events[1].Anonymous.mi.dwFlags, MOUSEEVENTF_WHEEL);
                assert_eq!(events[2].Anonymous.ki.dwFlags, KEYEVENTF_KEYUP);
            }
            let held = zoom_inputs(120, true);
            assert_eq!(held.len(), 1);
            assert_eq!(held[0].r#type, INPUT_MOUSE);
        }
    }
}

#[cfg(not(windows))]
impl InputSink for WindowsInput {
    fn execute(&self, _: &Command) -> Result<()> {
        bail!("接收端需要 Windows。");
    }
}

pub struct DryRun;
impl InputSink for DryRun {
    fn execute(&self, _: &Command) -> Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_chinese_emoji_newline_without_touching_clipboard() {
        let events = text_events("中😀\r\n\t");
        assert_eq!(events.len(), 10);
        assert_eq!(
            (events[0].scan, events[2].scan, events[4].scan),
            (0x4E2D, 0xD83D, 0xDE00)
        );
        assert_eq!((events[6].vk, events[8].vk), (0x0D, 0x09));
        assert!(
            events
                .chunks_exact(2)
                .all(|pair| pair[1].flags == pair[0].flags | 2)
        );
    }
}
