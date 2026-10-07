//! A normal receiver stays unelevated. Only the short, user-approved setup
//! invocation writes application-scoped, local-subnet firewall rules.
use std::{
    os::windows::ffi::OsStrExt,
    path::Path,
    sync::atomic::{AtomicBool, Ordering},
};

use anyhow::{Context, Result, bail};
use serde::Serialize;
use windows::{
    Win32::{
        Foundation::{RPC_E_CHANGED_MODE, VARIANT_TRUE},
        NetworkManagement::WindowsFirewall::{
            INetFwPolicy2, INetFwRule, INetFwRule3, NET_FW_ACTION_ALLOW, NET_FW_ACTION_BLOCK,
            NET_FW_PROFILE_TYPE2, NET_FW_RULE_DIR_IN, NetFwPolicy2, NetFwRule,
        },
        System::{
            Com::{
                CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, CoCreateInstance, CoInitializeEx,
                CoUninitialize,
            },
            Ole::IEnumVARIANT,
            Variant::{VARIANT, VT_DISPATCH, VariantClear},
        },
    },
    core::{BSTR, Interface},
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, WAIT_OBJECT_0},
    System::Threading::{GetExitCodeProcess, WaitForSingleObject},
    UI::{
        Shell::{SEE_MASK_NOASYNC, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW, ShellExecuteExW},
        WindowsAndMessaging::{
            IDYES, MB_ICONINFORMATION, MB_ICONQUESTION, MB_ICONWARNING, MB_OK, MB_SETFOREGROUND,
            MB_YESNO, MessageBoxW, SW_HIDE,
        },
    },
};

const GROUP: &str = "手机键鼠 · 局域网连接";
const PROFILE_MASK: i32 = 7;
static REQUEST_OPEN: AtomicBool = AtomicBool::new(false);
static PERMISSION_CHANGED: tokio::sync::Notify = tokio::sync::Notify::const_new();

#[derive(Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum LanStatus {
    Ready,
    Missing,
    Blocked,
}

struct ComApartment(bool);
impl ComApartment {
    fn enter() -> Result<Self> {
        let result = unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED) };
        if result == RPC_E_CHANGED_MODE {
            return Ok(Self(false));
        }
        result.ok().context("初始化网络权限检查失败")?;
        Ok(Self(true))
    }
}
impl Drop for ComApartment {
    fn drop(&mut self) {
        if self.0 {
            unsafe { CoUninitialize() }
        }
    }
}
struct VariantValue(VARIANT);
impl Drop for VariantValue {
    fn drop(&mut self) {
        unsafe {
            let _ = VariantClear(&mut self.0);
        }
    }
}

#[derive(Clone, Debug)]
struct Rule {
    program: String,
    profiles: i32,
    protocol: i32,
    ports: String,
    enabled: bool,
    inbound: bool,
    allow: bool,
    unrestricted: bool,
}

fn normalized_path(value: &str) -> String {
    value
        .trim_start_matches(r"\\?\")
        .replace('/', "\\")
        .to_lowercase()
}
fn any(value: &str) -> bool {
    value.is_empty() || value == "*"
}
fn port_matches(value: &str, port: u16) -> bool {
    any(value)
        || value.split(',').any(|entry| {
            let entry = entry.trim();
            if let Some((start, end)) = entry.split_once('-') {
                matches!((start.trim().parse::<u16>(), end.trim().parse::<u16>()),
                (Ok(start), Ok(end)) if start <= port && port <= end)
            } else {
                entry.parse::<u16>() == Ok(port)
            }
        })
}
fn required_ports(port: u16) -> [(i32, u16); 3] {
    [
        (6, port),
        (17, port),
        (17, phone_mouse_server::discovery::DISCOVERY_PORT),
    ]
}

fn classify(program: &str, profiles: i32, port: u16, rules: &[Rule]) -> LanStatus {
    let program = normalized_path(program);
    let mut missing = false;
    for (protocol, port) in required_ports(port) {
        let mut permitted = 0;
        let mut blocked = 0;
        for rule in rules {
            if !rule.enabled
                || !rule.inbound
                || !rule.unrestricted
                || !(rule.program.is_empty() || normalized_path(&rule.program) == program)
                || !(rule.protocol == 256 || rule.protocol == protocol)
                || !port_matches(&rule.ports, port)
            {
                continue;
            }
            if rule.allow {
                permitted |= rule.profiles;
            } else {
                blocked |= rule.profiles;
            }
        }
        if blocked & profiles != 0 {
            return LanStatus::Blocked;
        }
        missing |= permitted & profiles != profiles;
    }
    if missing {
        LanStatus::Missing
    } else {
        LanStatus::Ready
    }
}

fn policy() -> Result<INetFwPolicy2> {
    unsafe { CoCreateInstance(&NetFwPolicy2, None, CLSCTX_INPROC_SERVER) }
        .context("读取 Windows 网络权限失败")
}

fn read_rules(policy: &INetFwPolicy2) -> Result<Vec<Rule>> {
    let mut result = Vec::new();
    unsafe {
        let iterator: IEnumVARIANT = policy.Rules()?._NewEnum()?.cast()?;
        loop {
            let mut value = VariantValue(VARIANT::default());
            let mut fetched = 0;
            iterator
                .Next(std::slice::from_mut(&mut value.0), &mut fetched)
                .ok()?;
            if fetched == 0 {
                break;
            }
            let data = &value.0.Anonymous.Anonymous;
            if data.vt != VT_DISPATCH {
                continue;
            }
            let Some(dispatch) = data.Anonymous.pdispVal.as_ref() else {
                continue;
            };
            let rule: INetFwRule = dispatch.cast()?;
            if !rule.Enabled()?.as_bool() || rule.Direction()? != NET_FW_RULE_DIR_IN {
                continue;
            }
            let protocol = rule.Protocol()?;
            if ![6, 17, 256].contains(&protocol) {
                continue;
            }
            // Service-only and restricted endpoint rules do not grant general
            // LAN access to this receiver, even if they use the same port.
            let program = rule.ApplicationName()?.to_string();
            let service = rule.ServiceName()?.to_string();
            // Store-app rules often have an empty ApplicationName, but remain
            // scoped to their package. They must never count as a global grant.
            let package_restricted = if let Ok(rule3) = rule.cast::<INetFwRule3>() {
                !rule3.LocalAppPackageId()?.is_empty()
                    || !rule3.LocalUserOwner()?.is_empty()
                    || !rule3.LocalUserAuthorizedList()?.is_empty()
                    || !rule3.RemoteUserAuthorizedList()?.is_empty()
                    || !rule3.RemoteMachineAuthorizedList()?.is_empty()
                    || rule3.SecureFlags()? != 0
            } else {
                program.is_empty()
            };
            let remote = rule.RemoteAddresses()?.to_string();
            let local = rule.LocalAddresses()?.to_string();
            let remote_ports = if protocol == 256 {
                String::new()
            } else {
                rule.RemotePorts()?.to_string()
            };
            let interfaces = rule.InterfaceTypes()?.to_string();
            let unrestricted = service.is_empty()
                && !package_restricted
                && any(&local)
                && any(&remote_ports)
                && (any(&remote) || remote.eq_ignore_ascii_case("LocalSubnet"))
                && (any(&interfaces) || interfaces.eq_ignore_ascii_case("All"));
            let action = rule.Action()?;
            if action != NET_FW_ACTION_ALLOW && action != NET_FW_ACTION_BLOCK {
                continue;
            }
            result.push(Rule {
                program,
                profiles: rule.Profiles()?,
                protocol,
                ports: if protocol == 256 {
                    String::new()
                } else {
                    rule.LocalPorts()?.to_string()
                },
                enabled: true,
                inbound: true,
                allow: action == NET_FW_ACTION_ALLOW,
                unrestricted,
            });
        }
    }
    Ok(result)
}

fn inspect_policy(policy: &INetFwPolicy2, program: &str, port: u16) -> Result<LanStatus> {
    let current = unsafe { policy.CurrentProfileTypes()? } & PROFILE_MASK;
    if current == 0 {
        bail!("当前没有可用的网络，请连接 Wi-Fi 或有线网络后重试。");
    }
    let mut required = 0;
    for profile in [1, 2, 4] {
        if current & profile == 0 {
            continue;
        }
        unsafe {
            let profile_type = NET_FW_PROFILE_TYPE2(profile);
            if !policy.get_FirewallEnabled(profile_type)?.as_bool() {
                continue;
            }
            if policy.get_BlockAllInboundTraffic(profile_type)?.as_bool() {
                return Ok(LanStatus::Blocked);
            }
        }
        required |= profile;
    }
    Ok(classify(program, required, port, &read_rules(policy)?))
}

pub fn inspect(port: u16) -> Result<LanStatus> {
    let _apartment = ComApartment::enter()?;
    let program = std::env::current_exe()?;
    inspect_policy(&policy()?, program.to_string_lossy().as_ref(), port)
}

fn rule_prefix(program: &str, port: u16) -> String {
    // Stable across launches/builds, while copies in other folders and custom
    // ports get their own rules instead of changing an unrelated installation.
    let hash = normalized_path(program)
        .bytes()
        .fold(0xcbf29ce484222325u64, |hash, byte| {
            (hash ^ u64::from(byte)).wrapping_mul(0x100000001b3)
        });
    format!("PhoneMouse-LAN-{hash:016x}-{port}")
}

pub fn allow(port: u16) -> Result<()> {
    if port == 0 {
        bail!("网络端口不能为 0。");
    }
    let _apartment = ComApartment::enter()?;
    let policy = policy()?;
    let program = std::env::current_exe()?.to_string_lossy().into_owned();
    match inspect_policy(&policy, &program, port)? {
        LanStatus::Ready => return Ok(()),
        LanStatus::Blocked => {
            bail!("Windows 已明确阻止此连接，请在 Windows 安全中心检查阻止规则或管理策略。")
        }
        LanStatus::Missing => {}
    }
    let profiles = unsafe { policy.CurrentProfileTypes()? } & PROFILE_MASK;
    let prefix = rule_prefix(&program, port);
    unsafe {
        let rules = policy.Rules()?;
        for (suffix, protocol, ports) in [
            ("TCP", 6, port.to_string()),
            (
                "UDP",
                17,
                format!("{port},{}", phone_mouse_server::discovery::DISCOVERY_PORT),
            ),
        ] {
            let name = BSTR::from(format!("{prefix}-{suffix}"));
            let existing = rules.Item(&name).ok();
            // Only repair our exact application's rules; preserve other apps
            // and network profiles, and never disable a blocking policy.
            let (rule, existing_profiles) = if let Some(rule) = existing {
                if normalized_path(&rule.ApplicationName()?.to_string())
                    != normalized_path(&program)
                    || rule.Grouping()? != GROUP
                {
                    bail!("同名网络规则属于其他程序，未进行修改。");
                }
                let profiles = rule.Profiles()? & PROFILE_MASK;
                (rule, profiles)
            } else {
                (
                    CoCreateInstance::<_, INetFwRule>(&NetFwRule, None, CLSCTX_INPROC_SERVER)?,
                    0,
                )
            };
            rule.SetName(&name)?;
            rule.SetDescription(&BSTR::from("允许同一局域网的手机连接此接收端。"))?;
            rule.SetApplicationName(&BSTR::from(&program))?;
            rule.SetProtocol(protocol)?;
            rule.SetLocalPorts(&BSTR::from(ports))?;
            rule.SetRemotePorts(&BSTR::from("*"))?;
            rule.SetLocalAddresses(&BSTR::from("*"))?;
            rule.SetRemoteAddresses(&BSTR::from("LocalSubnet"))?;
            rule.SetInterfaceTypes(&BSTR::from("All"))?;
            rule.SetDirection(NET_FW_RULE_DIR_IN)?;
            rule.SetGrouping(&BSTR::from(GROUP))?;
            rule.SetProfiles(profiles | existing_profiles)?;
            rule.SetEdgeTraversal(windows::Win32::Foundation::VARIANT_FALSE)?;
            rule.SetAction(NET_FW_ACTION_ALLOW)?;
            rule.SetEnabled(VARIANT_TRUE)?;
            rules
                .Add(&rule)
                .context("保存联网权限失败，需要 Windows 管理员授权")?;
        }
    }
    if inspect_policy(&policy, &program, port)? != LanStatus::Ready {
        bail!("规则已保存，但 Windows 管理策略仍未允许连接。");
    }
    Ok(())
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}
fn message(text: &str, flags: u32) -> i32 {
    unsafe {
        MessageBoxW(
            std::ptr::null_mut(),
            wide(text).as_ptr(),
            wide("手机键鼠 · 允许手机连接").as_ptr(),
            flags | MB_SETFOREGROUND,
        )
    }
}
fn elevate(program: &Path, port: u16) -> Result<bool> {
    let _apartment = ComApartment::enter()?;
    let program: Vec<u16> = program.as_os_str().encode_wide().chain(Some(0)).collect();
    let verb = wide("runas");
    let parameters = wide(&format!("--allow-lan --port {port}"));
    let mut info = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC,
        lpVerb: verb.as_ptr(),
        lpFile: program.as_ptr(),
        lpParameters: parameters.as_ptr(),
        nShow: SW_HIDE,
        ..Default::default()
    };
    unsafe {
        if ShellExecuteExW(&mut info) == 0 {
            let error = std::io::Error::last_os_error();
            if error.raw_os_error() == Some(1223) {
                return Ok(false);
            }
            return Err(error).context("无法打开 Windows 管理员授权");
        }
        if info.hProcess.is_null() {
            bail!("无法确认联网授权的结果。");
        }
        let waited = WaitForSingleObject(info.hProcess, 60_000);
        let mut code = 1;
        let got_code = GetExitCodeProcess(info.hProcess, &mut code);
        CloseHandle(info.hProcess);
        if waited != WAIT_OBJECT_0 || got_code == 0 {
            bail!("联网授权尚未完成，请稍后从托盘菜单重试。");
        }
        if code != 0 {
            bail!("未能配置联网权限。请检查 Windows 授权或网络管理策略，然后从托盘菜单重试。");
        }
    }
    Ok(true)
}

pub async fn wait_for_permission(port: u16) {
    loop {
        PERMISSION_CHANGED.notified().await;
        if matches!(inspect(port), Ok(LanStatus::Ready)) {
            return;
        }
    }
}

pub fn request(port: u16, startup: bool) {
    if REQUEST_OPEN.swap(true, Ordering::AcqRel) {
        return;
    }
    let result = (|| -> Result<()> {
        match inspect(port)? {
            LanStatus::Ready => {
                PERMISSION_CHANGED.notify_one();
                if !startup {
                    message(
                        "已允许手机连接此电脑。\n\n保持手机与电脑在同一局域网即可，无需再次授权。",
                        MB_OK | MB_ICONINFORMATION,
                    );
                }
                return Ok(());
            }
            LanStatus::Blocked => {
                bail!("Windows 已明确阻止此连接。请在 Windows 安全中心检查阻止规则或网络管理策略。")
            }
            LanStatus::Missing => {}
        }
        if message(
            "需要允许同一局域网的手机连接此电脑。\n\n确认后，Windows 会请求一次管理员授权。权限会保存，以后普通启动即可。\n\n是否允许手机连接？",
            MB_YESNO | MB_ICONQUESTION,
        ) != IDYES
        {
            return Ok(());
        }
        if elevate(&std::env::current_exe()?, port)? {
            PERMISSION_CHANGED.notify_one();
            println!("已保存手机连接权限，以后普通启动即可。");
            if !startup {
                message(
                    "已允许手机连接。\n\n现在可以在手机上选择这台电脑，以后无需重复授权。",
                    MB_OK | MB_ICONINFORMATION,
                );
            }
        }
        Ok(())
    })();
    if let Err(error) = result {
        eprintln!("联网权限：{error:#}");
        message(
            &format!("{error:#}\n\n可以从托盘菜单「允许手机连接」重试。"),
            MB_OK | MB_ICONWARNING,
        );
    }
    REQUEST_OPEN.store(false, Ordering::Release);
}

#[cfg(test)]
mod tests {
    use super::*;
    const APP: &str = r"C:\PhoneMouse\phone-mouse-server.exe";
    fn rules(profiles: i32) -> Vec<Rule> {
        required_ports(8765)
            .into_iter()
            .map(|(protocol, port)| Rule {
                program: APP.into(),
                profiles,
                protocol,
                ports: port.to_string(),
                enabled: true,
                inbound: true,
                allow: true,
                unrestricted: true,
            })
            .collect()
    }
    #[test]
    fn public_rules_do_not_cover_private_wifi() {
        assert_eq!(classify(APP, 2, 8765, &rules(4)), LanStatus::Missing);
        assert_eq!(classify(APP, 6, 8765, &rules(6)), LanStatus::Ready);
    }
    #[test]
    fn other_executable_disabled_service_and_outbound_rules_do_not_cover_receiver() {
        for field in 0..4 {
            let mut rules = rules(7);
            for rule in &mut rules {
                match field {
                    0 => rule.program = "other.exe".into(),
                    1 => rule.enabled = false,
                    2 => rule.inbound = false,
                    _ => rule.unrestricted = false,
                }
            }
            assert_eq!(classify(APP, 2, 8765, &rules), LanStatus::Missing);
        }
    }
    #[test]
    fn tcp_udp_and_discovery_all_need_permission() {
        let mut without_discovery = rules(2);
        without_discovery.pop();
        assert_eq!(
            classify(APP, 2, 8765, &without_discovery),
            LanStatus::Missing
        );
        assert_eq!(classify(APP, 2, 9000, &rules(2)), LanStatus::Missing);
    }
    #[test]
    fn applicable_block_takes_precedence_without_affecting_other_profiles() {
        let mut rules = rules(7);
        let mut block = rules[0].clone();
        block.allow = false;
        block.profiles = 4;
        rules.push(block);
        assert_eq!(classify(APP, 2, 8765, &rules), LanStatus::Ready);
        assert_eq!(classify(APP, 4, 8765, &rules), LanStatus::Blocked);
    }
    #[test]
    fn legacy_all_port_rules_and_windows_path_case_are_recognized() {
        let mut rules = rules(7);
        for rule in &mut rules {
            rule.ports.clear();
            rule.program = APP.to_uppercase();
        }
        assert_eq!(
            classify(&format!(r"\\?\{APP}"), 6, 8765, &rules),
            LanStatus::Ready
        );
        assert!(port_matches("8000-9000,37655", 8765));
        assert!(!port_matches("8000-9000,37655", 9999));
    }
    #[test]
    fn rule_names_follow_executable_location_and_custom_port() {
        assert_eq!(
            rule_prefix(APP, 8765),
            rule_prefix(&APP.to_uppercase(), 8765)
        );
        assert_ne!(rule_prefix(APP, 8765), rule_prefix(APP, 9000));
        assert_ne!(rule_prefix(APP, 8765), rule_prefix("another.exe", 8765));
    }
}
