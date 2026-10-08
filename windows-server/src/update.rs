//! Lightweight Windows update checks backed by the release manifest.
//!
//! The receiver deliberately reads a static `latest.json` release asset instead
//! of calling the GitHub REST API. This keeps the check cheap and leaves room for
//! a second endpoint when a regional mirror is added later.

use std::{
    io::Read,
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::Duration,
};

use anyhow::{Context, Result, bail};
use serde::Deserialize;
use windows_sys::Win32::UI::{
    Shell::{SEE_MASK_NOASYNC, SHELLEXECUTEINFOW, ShellExecuteExW},
    WindowsAndMessaging::{
        IDYES, MB_ICONINFORMATION, MB_ICONQUESTION, MB_SETFOREGROUND, MB_YESNO, MessageBoxW,
        SW_SHOWNORMAL,
    },
};

const ENDPOINTS: &[&str] =
    &["https://github.com/windyy0/phone-mouse/releases/latest/download/latest.json"];
const CHECK_INTERVAL: Duration = Duration::from_secs(6 * 60 * 60);
const INITIAL_DELAY: Duration = Duration::from_secs(15);
const MAX_MANIFEST_BYTES: u64 = 128 * 1024;
static CHECK_IN_FLIGHT: AtomicBool = AtomicBool::new(false);
static PROMPTED_VERSION: OnceLock<Mutex<Option<String>>> = OnceLock::new();

#[derive(Debug, Deserialize)]
struct Manifest {
    schema: u32,
    version: String,
    #[serde(default)]
    release_page: String,
    #[serde(default)]
    notes: String,
    windows: PlatformUpdate,
}

#[derive(Debug, Deserialize)]
struct PlatformUpdate {
    #[serde(default)]
    version: String,
    url: String,
}

#[derive(Debug)]
struct AvailableUpdate {
    version: String,
    release_page: String,
    notes: String,
}

struct CheckGuard;

impl Drop for CheckGuard {
    fn drop(&mut self) {
        CHECK_IN_FLIGHT.store(false, Ordering::Release);
    }
}

pub fn spawn() {
    let _ = thread::Builder::new()
        .name("phone-mouse-update".to_string())
        .spawn(|| {
            thread::sleep(INITIAL_DELAY);
            loop {
                run_check();
                thread::sleep(CHECK_INTERVAL);
            }
        });
}

pub fn request_manual_check() {
    if CHECK_IN_FLIGHT.swap(true, Ordering::AcqRel) {
        return;
    }
    let result = thread::Builder::new()
        .name("phone-mouse-manual-update".to_string())
        .spawn(|| {
            let _guard = CheckGuard;
            run_check_inner(true);
        });
    if result.is_err() {
        CHECK_IN_FLIGHT.store(false, Ordering::Release);
    }
}

fn run_check() {
    if CHECK_IN_FLIGHT.swap(true, Ordering::AcqRel) {
        return;
    }
    let _guard = CheckGuard;
    run_check_inner(false);
}

fn run_check_inner(manual: bool) {
    match fetch_latest() {
        Ok(Some(update)) => {
            if !manual && already_prompted(&update.version) {
                return;
            }
            mark_prompted(&update.version);
            let mut message = format!(
                "发现手机键鼠新版本 v{}。\n\n当前版本：v{}",
                update.version,
                env!("CARGO_PKG_VERSION"),
            );
            if !update.notes.trim().is_empty() {
                message.push_str("\n\n");
                message.push_str(&truncate(&update.notes, 1200));
            }
            message.push_str("\n\n是否打开发布页下载新版本？");
            let answer = message_box(&message, MB_YESNO | MB_ICONQUESTION);
            if answer == IDYES {
                if let Err(error) = open_url(&update.release_page) {
                    message_box(&format!("无法打开更新页面：{error:#}"), MB_ICONINFORMATION);
                }
            }
        }
        Ok(None) => {
            if manual {
                message_box(
                    &format!("当前已是最新版本 v{}。", env!("CARGO_PKG_VERSION")),
                    MB_ICONINFORMATION,
                );
            }
        }
        Err(error) if manual => {
            message_box(&format!("检查更新失败：{error:#}"), MB_ICONINFORMATION);
        }
        Err(_) => {}
    }
}

fn fetch_latest() -> Result<Option<AvailableUpdate>> {
    let client = reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(8))
        .timeout(Duration::from_secs(12))
        .user_agent(format!("phone-mouse-windows/{}", env!("CARGO_PKG_VERSION")))
        .build()
        .context("创建更新检查客户端失败")?;
    fetch_from_endpoints(&client, ENDPOINTS, env!("CARGO_PKG_VERSION"))
}

fn fetch_from_endpoints(
    client: &reqwest::blocking::Client,
    endpoints: &[&str],
    current_version: &str,
) -> Result<Option<AvailableUpdate>> {
    let mut last_error = None;
    for endpoint in endpoints {
        let result = (|| -> Result<Option<AvailableUpdate>> {
            let response = client
                .get(*endpoint)
                .header("Accept", "application/json")
                .send()?
                .error_for_status()?;
            let mut body = Vec::new();
            response
                .take(MAX_MANIFEST_BYTES + 1)
                .read_to_end(&mut body)?;
            if body.len() as u64 > MAX_MANIFEST_BYTES {
                bail!("更新清单过大");
            }
            select_update(serde_json::from_slice(&body)?, current_version)
        })();
        match result {
            Ok(update) => return Ok(update),
            Err(error) => last_error = Some(error),
        }
    }
    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("没有可用的更新地址")))
}

fn select_update(manifest: Manifest, current_version: &str) -> Result<Option<AvailableUpdate>> {
    if manifest.schema != 1 || manifest.version != manifest.windows.version {
        bail!("更新清单版本不一致或格式不受支持");
    }
    let latest = parse_version(&manifest.version)?;
    let current = parse_version(current_version)?;
    validate_url(&manifest.windows.url)?;
    validate_url(&manifest.release_page)?;
    if latest <= current {
        return Ok(None);
    }
    Ok(Some(AvailableUpdate {
        version: manifest.version,
        release_page: manifest.release_page,
        notes: manifest.notes,
    }))
}

fn parse_version(value: &str) -> Result<[u64; 3]> {
    let parts = value.split('.').collect::<Vec<_>>();
    if parts.len() != 3
        || parts.iter().any(|part| {
            part.is_empty()
                || !part.bytes().all(|byte| byte.is_ascii_digit())
                || (part.len() > 1 && part.starts_with('0'))
        })
    {
        bail!("无效的版本号：{value}");
    }
    Ok([parts[0].parse()?, parts[1].parse()?, parts[2].parse()?])
}

fn validate_url(value: &str) -> Result<()> {
    let url = reqwest::Url::parse(value).context("无效的更新地址")?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || value.contains('\0')
    {
        bail!("更新地址必须是 HTTPS 链接");
    }
    Ok(())
}

fn already_prompted(version: &str) -> bool {
    PROMPTED_VERSION
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map(|value| value.as_deref() == Some(version))
        .unwrap_or(false)
}

fn mark_prompted(version: &str) {
    if let Ok(mut value) = PROMPTED_VERSION.get_or_init(|| Mutex::new(None)).lock() {
        *value = Some(version.to_string());
    }
}

fn truncate(value: &str, max_chars: usize) -> String {
    let mut output = value.chars().take(max_chars).collect::<String>();
    if value.chars().count() > max_chars {
        output.push('…');
    }
    output
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}

fn message_box(text: &str, flags: u32) -> i32 {
    unsafe {
        MessageBoxW(
            std::ptr::null_mut(),
            wide(text).as_ptr(),
            wide("手机键鼠 · 检查更新").as_ptr(),
            flags | MB_SETFOREGROUND,
        )
    }
}

fn open_url(url: &str) -> Result<()> {
    validate_url(url)?;
    let verb = wide("open");
    let file = wide(url);
    let mut info = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOASYNC,
        lpVerb: verb.as_ptr(),
        lpFile: file.as_ptr(),
        nShow: SW_SHOWNORMAL,
        ..Default::default()
    };
    if unsafe { ShellExecuteExW(&mut info) } == 0 {
        return Err(std::io::Error::last_os_error()).context("打开更新页面失败");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{io::Write, net::TcpListener};

    fn fixture(version: &str) -> Manifest {
        Manifest {
            schema: 1,
            version: version.to_string(),
            release_page: "https://github.com/windyy0/phone-mouse/releases/tag/v1.1.0".into(),
            notes: "改进更新提示".into(),
            windows: PlatformUpdate {
                version: version.into(),
                url: "https://github.com/windyy0/phone-mouse/releases/download/v1.1.0/app.exe"
                    .into(),
            },
        }
    }

    #[test]
    fn compares_numeric_stable_versions_and_never_downgrades() {
        assert!(select_update(fixture("1.10.0"), "1.9.9").unwrap().is_some());
        assert!(select_update(fixture("1.0.0"), "1.0.0").unwrap().is_none());
        assert!(select_update(fixture("1.9.9"), "1.10.0").unwrap().is_none());
        assert!(
            select_update(fixture("2.0.0"), "1.99.99")
                .unwrap()
                .is_some()
        );
    }

    #[test]
    fn rejects_invalid_versions_instead_of_reporting_up_to_date() {
        for value in [
            "",
            "1",
            "1.0",
            "1.0.0.1",
            "v1.0.0",
            "01.0.0",
            "1.0.0-beta",
            "1.a.0",
        ] {
            assert!(select_update(fixture(value), "1.0.0").is_err(), "{value}");
        }
    }

    #[test]
    fn rejects_unsupported_or_inconsistent_manifest() {
        let mut manifest = fixture("1.1.0");
        manifest.schema = 2;
        assert!(select_update(manifest, "1.0.0").is_err());
        let mut manifest = fixture("1.1.0");
        manifest.windows.version = "1.2.0".into();
        assert!(select_update(manifest, "1.0.0").is_err());
        assert!(serde_json::from_str::<Manifest>("{}").is_err());
    }

    #[test]
    fn rejects_unsafe_links_even_for_an_older_release() {
        for value in [
            "http://example.com/app.exe",
            "file:///app.exe",
            "https://",
            "https://user@example.com/app.exe",
        ] {
            let mut manifest = fixture("1.0.0");
            manifest.windows.url = value.into();
            assert!(select_update(manifest, "1.1.0").is_err());
            let mut manifest = fixture("1.1.0");
            manifest.release_page = value.into();
            assert!(select_update(manifest, "1.0.0").is_err());
        }
    }

    #[test]
    fn falls_back_when_the_first_endpoint_returns_an_invalid_manifest() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let worker = thread::spawn(move || {
            let valid = r#"{"schema":1,"version":"1.1.0","release_page":"https://example.com/release","windows":{"version":"1.1.0","url":"https://example.com/app.exe"}}"#;
            for body in ["<html>unavailable</html>", valid] {
                let (mut stream, _) = listener.accept().unwrap();
                let mut request = [0; 4096];
                let received = stream.read(&mut request).unwrap();
                assert!(received > 0);
                write!(
                    stream,
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                )
                .unwrap();
            }
        });
        let client = reqwest::blocking::Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(2))
            .build()
            .unwrap();
        let result = fetch_from_endpoints(
            &client,
            &[&format!("{base}/primary"), &format!("{base}/mirror")],
            "1.0.0",
        );
        worker.join().unwrap();
        assert_eq!(result.unwrap().unwrap().version, "1.1.0");
    }
}
