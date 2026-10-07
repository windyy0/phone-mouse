use serde::{Deserialize, Serialize};

pub const PROTOCOL: u32 = 1;
pub const MAX_TEXT: usize = 2000;

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum Command {
    Hello { protocol: u32 },
    MovePixels { dx: i32, dy: i32 },
    Click { button: Button },
    ButtonDown { button: Button },
    ButtonUp { button: Button },
    Scroll { delta: i32 },
    TouchGesture { horizontal: i32, zoom: i32 },
    Key { key: Key },
    Text { text: String, id: u64 },
    Ping { id: u64 },
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Button {
    Left,
    Right,
    Middle,
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Key {
    Enter,
    Backspace,
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Reply {
    Ready {
        protocol: u32,
        version: &'static str,
        udp_port: u16,
        udp_token: String,
    },
    TextSent {
        id: u64,
    },
    Pong {
        id: u64,
    },
    Error {
        code: &'static str,
        message: &'static str,
        #[serde(skip_serializing_if = "Option::is_none")]
        id: Option<u64>,
    },
}

impl Command {
    pub fn validate(&self) -> Result<(), &'static str> {
        match self {
            Self::MovePixels { dx, dy } if dx.unsigned_abs() > 2000 || dy.unsigned_abs() > 2000 => {
                Err("鼠标位移超出范围。")
            }
            Self::Scroll { delta } if delta.unsigned_abs() > 2400 => Err("滚动量超出范围。"),
            Self::TouchGesture { horizontal, zoom }
                if horizontal.unsigned_abs() > 2400
                    || zoom.unsigned_abs() > 2400
                    || zoom % 120 != 0 =>
            {
                Err("双指手势超出范围或缩放步进不正确。")
            }
            Self::Text { text, .. }
                if text.is_empty()
                    || text.chars().count() > MAX_TEXT
                    || text
                        .chars()
                        .any(|c| c.is_control() && !matches!(c, '\r' | '\n' | '\t')) =>
            {
                Err("文字长度或内容不符合要求。")
            }
            Self::Hello { .. } => Err("连接已完成握手，请勿重复发送。"),
            _ => Ok(()),
        }
    }

    pub fn id(&self) -> Option<u64> {
        match self {
            Self::Text { id, .. } | Self::Ping { id } => Some(*id),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_bad_input_and_handles_integer_minimum() {
        for command in [
            Command::MovePixels {
                dx: i32::MIN,
                dy: 0,
            },
            Command::Scroll { delta: 2401 },
            Command::Text {
                text: "bad\0text".into(),
                id: 1,
            },
            Command::Text {
                text: String::new(),
                id: 2,
            },
        ] {
            assert!(command.validate().is_err());
        }
        assert!(
            serde_json::from_str::<Command>(r#"{"type":"move_pixels","dx":1.5,"dy":0}"#).is_err()
        );
        assert!(serde_json::from_str::<Command>(r#"{"type":"click","button":"side"}"#).is_err());
    }

    #[test]
    fn accepts_middle_click_press_and_release() {
        for kind in ["click", "button_down", "button_up"] {
            let command: Command =
                serde_json::from_str(&format!(r#"{{"type":"{kind}","button":"middle"}}"#)).unwrap();
            assert!(matches!(
                command,
                Command::Click {
                    button: Button::Middle
                } | Command::ButtonDown {
                    button: Button::Middle
                } | Command::ButtonUp {
                    button: Button::Middle
                }
            ));
            assert!(command.validate().is_ok());
        }
    }
    #[test]
    fn touch_gestures_require_bounded_signed_values_and_complete_zoom_notches() {
        let parsed: Command =
            serde_json::from_str(r#"{"type":"touch_gesture","horizontal":-120,"zoom":240}"#)
                .unwrap();
        assert!(parsed.validate().is_ok());
        for command in [
            Command::TouchGesture {
                horizontal: i32::MIN,
                zoom: 0,
            },
            Command::TouchGesture {
                horizontal: 0,
                zoom: i32::MIN,
            },
            Command::TouchGesture {
                horizontal: 2401,
                zoom: 0,
            },
            Command::TouchGesture {
                horizontal: 0,
                zoom: 1201,
            },
        ] {
            assert!(command.validate().is_err());
        }
        assert!(
            serde_json::from_str::<Command>(
                r#"{"type":"touch_gesture","horizontal":1.5,"zoom":120}"#
            )
            .is_err()
        );
    }

    #[test]
    fn pixel_motion_accepts_signed_integers_within_bounds() {
        let command: Command =
            serde_json::from_str(r#"{"type":"move_pixels","dx":12,"dy":-5}"#).unwrap();
        assert!(matches!(command, Command::MovePixels { dx: 12, dy: -5 }));
        assert!(command.validate().is_ok());
        assert!(
            Command::MovePixels {
                dx: i32::MIN,
                dy: 0
            }
            .validate()
            .is_err()
        );
        assert!(Command::MovePixels { dx: 0, dy: 2001 }.validate().is_err());
    }

    #[test]
    fn rejects_obsolete_commands_and_unknown_handshake_fields() {
        for value in [
            r#"{"type":"move","dx":12,"dy":-5}"#,
            r#"{"type":"auth","protocol":1,"password":""}"#,
            r#"{"type":"hello","protocol":1,"password":""}"#,
        ] {
            assert!(serde_json::from_str::<Command>(value).is_err());
        }
        assert!(matches!(
            serde_json::from_str::<Command>(r#"{"type":"hello","protocol":1}"#).unwrap(),
            Command::Hello { protocol: PROTOCOL }
        ));
    }

    #[test]
    fn chinese_limit_is_by_characters_not_utf8_bytes() {
        assert!(
            Command::Text {
                text: "中文😀\n".repeat(500),
                id: 1
            }
            .validate()
            .is_ok()
        );
        assert!(
            Command::Text {
                text: "中".repeat(2001),
                id: 1
            }
            .validate()
            .is_err()
        );
    }
}
