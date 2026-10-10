//! Connection ownership and input barriers shared by the network and tray.
use crate::{
    input::InputSink,
    protocol::{Button, Command, LaserSettings, PresentationMode, PresentationState},
};
use anyhow::Result;
use std::{
    collections::HashMap,
    net::IpAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::watch;

pub type SessionId = u64;
pub const LASER_LEASE: Duration = Duration::from_secs(8);
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

pub trait LaserSurface: Send + Sync {
    fn show(&self, settings: LaserSettings) -> Result<()>;
    fn configure(&self, settings: LaserSettings) -> Result<()>;
    /// Keep a remote pointer move and its cursor image in one ordered operation.
    /// The native surface performs both on its rendering thread; other surfaces
    /// retain the existing input semantics by default.
    fn move_pointer(&self, dx: i32, dy: i32, input: Arc<dyn InputSink>) -> Result<()> {
        input.execute(&Command::MovePixels { dx, dy })
    }
    fn hide(&self) -> Result<()>;
    fn shutdown(&self);
    fn healthy(&self) -> bool {
        true
    }
}

pub struct DryRunLaser;
impl LaserSurface for DryRunLaser {
    fn show(&self, _: LaserSettings) -> Result<()> {
        Ok(())
    }
    fn configure(&self, _: LaserSettings) -> Result<()> {
        Ok(())
    }
    fn hide(&self) -> Result<()> {
        Ok(())
    }
    fn shutdown(&self) {}
}

struct Session {
    peer: IpAddr,
    token: [u8; 16],
    sequence: u64,
    window: Instant,
    count: u32,
    mode: PresentationMode,
    held: Vec<Button>,
    refreshed: Instant,
    events: watch::Sender<PresentationState>,
}
#[derive(Default)]
struct State {
    sessions: HashMap<SessionId, Session>,
    tokens: HashMap<[u8; 16], SessionId>,
    owner: Option<SessionId>,
}

pub struct Registration {
    pub id: SessionId,
    pub token: [u8; 16],
    pub events: watch::Receiver<PresentationState>,
}

pub struct PresentationController {
    input: Arc<dyn InputSink>,
    laser: Arc<dyn LaserSurface>,
    state: Mutex<State>,
}

fn fresh_token() -> [u8; 16] {
    let mut token = [0; 16];
    let time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    token[..8].copy_from_slice(&time.to_be_bytes());
    token[8..].copy_from_slice(&NEXT_ID.fetch_add(1, Ordering::Relaxed).to_be_bytes());
    token
}
pub fn encode_token(token: &[u8; 16]) -> String {
    token.iter().map(|byte| format!("{byte:02x}")).collect()
}
fn snapshot(session: &Session, reason: Option<&'static str>) -> PresentationState {
    PresentationState {
        mode: session.mode,
        udp_token: encode_token(&session.token),
        reason,
    }
}

impl PresentationController {
    pub fn new(input: Arc<dyn InputSink>, laser: Arc<dyn LaserSurface>) -> Arc<Self> {
        Arc::new(Self {
            input,
            laser,
            state: Mutex::new(State::default()),
        })
    }
    pub fn register(&self, peer: IpAddr) -> Registration {
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let token = fresh_token();
        let (events, receiver) = watch::channel(PresentationState {
            mode: PresentationMode::Mouse,
            udp_token: encode_token(&token),
            reason: None,
        });
        let session = Session {
            peer,
            token,
            sequence: 0,
            window: Instant::now(),
            count: 0,
            mode: PresentationMode::Mouse,
            held: Vec::new(),
            refreshed: Instant::now(),
            events,
        };
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.tokens.insert(token, id);
        state.sessions.insert(id, session);
        Registration {
            id,
            token,
            events: receiver,
        }
    }
    fn rotate(state: &mut State, id: SessionId) {
        let session = state.sessions.get_mut(&id).expect("registered session");
        state.tokens.remove(&session.token);
        session.token = fresh_token();
        session.sequence = 0;
        // Keep the rate window across token rotations.
        state.tokens.insert(session.token, id);
    }
    fn release_buttons(&self, state: &mut State, id: SessionId) {
        let held = state
            .sessions
            .get_mut(&id)
            .map(|session| std::mem::take(&mut session.held))
            .unwrap_or_default();
        for button in held {
            if !state
                .sessions
                .values()
                .any(|session| session.held.contains(&button))
            {
                let _ = self.input.execute(&Command::ButtonUp { button });
            }
        }
    }
    pub fn switch(
        &self,
        id: SessionId,
        mode: PresentationMode,
        settings: LaserSettings,
    ) -> Result<PresentationState, &'static str> {
        settings.validate()?;
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let previous = state.sessions.get(&id).ok_or("连接已失效。")?.mode;
        // The client has already cancelled its gestures. Even a denied or
        // redundant switch must release this session's earlier button holds.
        self.release_buttons(&mut state, id);
        if mode == PresentationMode::Laser && state.owner.is_some_and(|owner| owner != id) {
            return Err("另一台手机正在使用激光笔。");
        }
        if previous == mode {
            return Ok(snapshot(state.sessions.get(&id).unwrap(), None));
        }
        if mode == PresentationMode::Laser {
            if let Err(error) = self.laser.show(settings) {
                eprintln!("激光覆盖层启动失败：{error:#}");
                return Err("激光笔启动失败，请检查接收端。");
            }
            state.owner = Some(id);
        } else if state.owner == Some(id) {
            if let Err(error) = self.laser.hide() {
                eprintln!("关闭激光失败：{error:#}");
                return Err("激光笔关闭失败，请检查接收端。");
            }
            state.owner = None;
        }
        Self::rotate(&mut state, id);
        let session = state.sessions.get_mut(&id).unwrap();
        session.mode = mode;
        session.refreshed = Instant::now();
        let current = snapshot(session, None);
        session.events.send_replace(current.clone());
        Ok(current)
    }
    pub fn configure(
        &self,
        id: SessionId,
        settings: LaserSettings,
    ) -> Result<PresentationState, &'static str> {
        settings.validate()?;
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.owner != Some(id) {
            return Err("请先开启激光笔。");
        }
        self.laser
            .configure(settings)
            .map_err(|_| "激光笔设置未能应用。")?;
        Ok(snapshot(state.sessions.get(&id).unwrap(), None))
    }
    pub fn ping(&self, id: SessionId) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(session) = state.sessions.get_mut(&id) {
            session.refreshed = Instant::now();
        }
    }
    fn execute_locked(&self, state: &mut State, id: SessionId, command: &Command) -> Result<()> {
        let Some(session) = state.sessions.get(&id) else {
            anyhow::bail!("connection expired");
        };
        if matches!(command, Command::MovePixels { .. })
            && state.owner.is_some_and(|owner| owner != id)
        {
            return Ok(());
        }
        match command {
            Command::MovePixels { dx, dy } if state.owner == Some(id) => {
                self.laser.move_pointer(*dx, *dy, self.input.clone())?;
            }
            Command::ButtonDown { button } => {
                if session.held.contains(button) {
                    return Ok(());
                }
                if !state
                    .sessions
                    .values()
                    .any(|other| other.held.contains(button))
                {
                    self.input.execute(command)?;
                }
                state.sessions.get_mut(&id).unwrap().held.push(*button);
            }
            Command::ButtonUp { button } => {
                if !session.held.contains(button) {
                    return Ok(());
                }
                let held_elsewhere = state
                    .sessions
                    .iter()
                    .any(|(other_id, other)| *other_id != id && other.held.contains(button));
                if !held_elsewhere {
                    self.input.execute(command)?;
                }
                state
                    .sessions
                    .get_mut(&id)
                    .unwrap()
                    .held
                    .retain(|held| held != button);
            }
            Command::Click { button }
                if state
                    .sessions
                    .values()
                    .any(|other| other.held.contains(button)) => {}
            _ => self.input.execute(command)?,
        }
        Ok(())
    }
    pub fn execute(&self, id: SessionId, command: &Command) -> Result<()> {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        self.execute_locked(&mut state, id, command)
    }
    pub fn udp(&self, peer: IpAddr, token: [u8; 16], sequence: u64, dx: i32, dy: i32, wheel: i32) {
        let movement = Command::MovePixels { dx, dy };
        let scrolling = Command::Scroll { delta: wheel };
        if movement.validate().is_err() || scrolling.validate().is_err() {
            return;
        }
        // Validation, execution and mode/token changes share this lock. A packet
        // cannot pass validation, then execute after a concurrent switch.
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let Some(&id) = state.tokens.get(&token) else {
            return;
        };
        let session = state.sessions.get_mut(&id).unwrap();
        if session.peer != peer || sequence == 0 || sequence <= session.sequence {
            return;
        }
        if session.window.elapsed() >= Duration::from_secs(1) {
            session.window = Instant::now();
            session.count = 0;
        }
        if session.count >= 300 {
            return;
        }
        session.sequence = sequence;
        session.count += 1;
        // Laser is a visual treatment of the real cursor; all existing mouse
        // operations keep their normal semantics in both presentation modes.
        if dx != 0 || dy != 0 {
            let _ = self.execute_locked(&mut state, id, &movement);
        }
        if wheel != 0 {
            let _ = self.execute_locked(&mut state, id, &scrolling);
        }
    }
    fn exit_locked(&self, state: &mut State, reason: &'static str) {
        let Some(id) = state.owner else {
            return;
        };
        if let Err(error) = self.laser.hide() {
            eprintln!("关闭激光失败：{error:#}");
            self.laser.shutdown();
        }
        state.owner = None;
        self.release_buttons(state, id);
        Self::rotate(state, id);
        let session = state.sessions.get_mut(&id).unwrap();
        session.mode = PresentationMode::Mouse;
        session.events.send_replace(snapshot(session, Some(reason)));
    }
    pub fn force_exit(&self, reason: &'static str) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        self.exit_locked(&mut state, reason);
    }
    pub fn expire(&self) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.owner.is_some() && !self.laser.healthy() {
            self.exit_locked(&mut state, "overlay_error");
            return;
        }
        if state
            .owner
            .and_then(|id| state.sessions.get(&id))
            .is_some_and(|session| session.refreshed.elapsed() >= LASER_LEASE)
        {
            self.exit_locked(&mut state, "lease_expired");
        }
    }
    pub fn disconnect(&self, id: SessionId) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.owner == Some(id) {
            self.exit_locked(&mut state, "disconnected");
        } else {
            self.release_buttons(&mut state, id);
        }
        if let Some(session) = state.sessions.remove(&id) {
            state.tokens.remove(&session.token);
        }
    }
    pub fn shutdown(&self) {
        self.force_exit("shutdown");
        self.laser.shutdown();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[derive(Default)]
    struct RecordingInput(Mutex<Vec<String>>);
    impl InputSink for RecordingInput {
        fn execute(&self, command: &Command) -> Result<()> {
            self.0.lock().unwrap().push(format!("{command:?}"));
            Ok(())
        }
    }
    #[derive(Default)]
    struct Surface {
        shows: AtomicU64,
        hides: AtomicU64,
        moves: AtomicU64,
        hide_error: std::sync::atomic::AtomicBool,
    }
    impl LaserSurface for Surface {
        fn show(&self, _: LaserSettings) -> Result<()> {
            self.shows.fetch_add(1, Ordering::Relaxed);
            Ok(())
        }
        fn configure(&self, _: LaserSettings) -> Result<()> {
            Ok(())
        }
        fn move_pointer(&self, dx: i32, dy: i32, input: Arc<dyn InputSink>) -> Result<()> {
            self.moves.fetch_add(1, Ordering::Relaxed);
            input.execute(&Command::MovePixels { dx, dy })
        }
        fn hide(&self) -> Result<()> {
            self.hides.fetch_add(1, Ordering::Relaxed);
            if self.hide_error.load(Ordering::Relaxed) {
                anyhow::bail!("hide failed");
            }
            Ok(())
        }
        fn shutdown(&self) {}
    }
    fn fixture() -> (
        Arc<PresentationController>,
        Arc<RecordingInput>,
        Arc<Surface>,
        IpAddr,
    ) {
        let input = Arc::new(RecordingInput::default());
        let laser = Arc::new(Surface::default());
        (
            PresentationController::new(input.clone(), laser.clone()),
            input,
            laser,
            "127.0.0.1".parse().unwrap(),
        )
    }
    fn token(controller: &PresentationController, id: SessionId) -> [u8; 16] {
        controller.state.lock().unwrap().sessions[&id].token
    }
    #[test]
    fn failed_normal_hide_keeps_owner_until_forced_recovery() {
        let (controller, _, laser, peer) = fixture();
        let owner = controller.register(peer);
        let other = controller.register(peer);
        controller
            .switch(owner.id, PresentationMode::Laser, LaserSettings::default())
            .unwrap();
        laser.hide_error.store(true, Ordering::Relaxed);
        assert_eq!(
            controller
                .switch(owner.id, PresentationMode::Mouse, LaserSettings::default())
                .unwrap_err(),
            "激光笔关闭失败，请检查接收端。"
        );
        assert_eq!(
            controller
                .switch(other.id, PresentationMode::Laser, LaserSettings::default())
                .unwrap_err(),
            "另一台手机正在使用激光笔。"
        );
        controller.force_exit("overlay_error");
        assert_eq!(controller.state.lock().unwrap().owner, None);
    }
    #[test]
    fn only_the_owner_routes_moves_through_the_surface_and_switches_revoke_old_packets() {
        let (controller, input, laser, peer) = fixture();
        let owner = controller.register(peer);
        let other = controller.register(peer);
        controller.udp(peer, owner.token, 1, 1, 0, 0);
        assert_eq!(laser.moves.load(Ordering::Relaxed), 0);
        controller
            .switch(owner.id, PresentationMode::Laser, LaserSettings::default())
            .unwrap();
        let active_token = token(&controller, owner.id);
        controller.udp(peer, active_token, 1, 2, 0, 0);
        controller
            .execute(owner.id, &Command::MovePixels { dx: 3, dy: 0 })
            .unwrap();
        controller.udp(peer, other.token, 1, 999, 999, 0);
        controller
            .execute(other.id, &Command::MovePixels { dx: 999, dy: 999 })
            .unwrap();
        assert_eq!(laser.moves.load(Ordering::Relaxed), 2);
        controller.force_exit("tray");
        controller.udp(peer, active_token, 2, 999, 999, 0);
        controller.udp(peer, token(&controller, owner.id), 1, 4, 0, 0);
        assert_eq!(laser.moves.load(Ordering::Relaxed), 2);
        assert_eq!(
            input.0.lock().unwrap().as_slice(),
            [
                "MovePixels { dx: 1, dy: 0 }",
                "MovePixels { dx: 2, dy: 0 }",
                "MovePixels { dx: 3, dy: 0 }",
                "MovePixels { dx: 4, dy: 0 }",
            ]
        );
    }
    #[test]
    fn every_switch_discards_delayed_udp_and_retains_normal_mouse_operations() {
        let (controller, input, _, peer) = fixture();
        let session = controller.register(peer);
        controller.udp(peer, session.token, 1, 5, 0, 0);
        controller
            .execute(
                session.id,
                &Command::ButtonDown {
                    button: Button::Left,
                },
            )
            .unwrap();
        controller
            .switch(
                session.id,
                PresentationMode::Laser,
                LaserSettings::default(),
            )
            .unwrap();
        let laser_token = token(&controller, session.id);
        assert_ne!(laser_token, session.token);
        controller.udp(peer, session.token, 900, 999, 999, 120);
        controller.udp(peer, laser_token, 1, 7, 0, 120);
        for command in [
            Command::Click {
                button: Button::Right,
            },
            Command::TouchGesture {
                horizontal: 120,
                zoom: -120,
            },
            Command::Key {
                key: crate::protocol::Key::Enter,
            },
        ] {
            controller.execute(session.id, &command).unwrap();
        }
        controller
            .switch(
                session.id,
                PresentationMode::Mouse,
                LaserSettings::default(),
            )
            .unwrap();
        controller.udp(peer, laser_token, 902, 999, 999, 120);
        controller.udp(peer, token(&controller, session.id), 1, 9, 0, 0);
        let events = input.0.lock().unwrap();
        assert_eq!(events.len(), 9);
        assert!(events.iter().all(|event| !event.contains("999")));
        assert_eq!(events[2], "ButtonUp { button: Left }");
        assert!(events.iter().any(|event| event.starts_with("TouchGesture")));
    }
    #[test]
    fn same_ip_sessions_cannot_steal_move_or_close_anothers_laser() {
        let (controller, input, laser, peer) = fixture();
        let a = controller.register(peer);
        let b = controller.register(peer);
        controller
            .switch(a.id, PresentationMode::Laser, LaserSettings::default())
            .unwrap();
        assert!(
            controller
                .switch(b.id, PresentationMode::Laser, LaserSettings::default())
                .is_err()
        );
        assert!(
            controller
                .configure(b.id, LaserSettings::default())
                .is_err()
        );
        controller
            .switch(b.id, PresentationMode::Mouse, LaserSettings::default())
            .unwrap();
        controller.udp(peer, b.token, 1, 999, 999, 0);
        controller.disconnect(b.id);
        assert_eq!(laser.hides.load(Ordering::Relaxed), 0);
        controller.udp(peer, token(&controller, a.id), 1, 8, 0, 0);
        assert_eq!(input.0.lock().unwrap().len(), 1);
        controller.disconnect(a.id);
        let new = controller.register(peer);
        controller
            .switch(new.id, PresentationMode::Laser, LaserSettings::default())
            .unwrap();
        controller.disconnect(a.id);
        assert_eq!(controller.state.lock().unwrap().owner, Some(new.id));
        assert_eq!(laser.hides.load(Ordering::Relaxed), 1);
    }
    #[test]
    fn lease_and_tray_exit_publish_state_and_revoke_old_tokens() {
        let (controller, input, laser, peer) = fixture();
        let mut session = controller.register(peer);
        controller
            .switch(
                session.id,
                PresentationMode::Laser,
                LaserSettings::default(),
            )
            .unwrap();
        session.events.borrow_and_update();
        let old = token(&controller, session.id);
        controller
            .state
            .lock()
            .unwrap()
            .sessions
            .get_mut(&session.id)
            .unwrap()
            .refreshed = Instant::now() - LASER_LEASE;
        controller.ping(session.id);
        controller.expire();
        assert_eq!(laser.hides.load(Ordering::Relaxed), 0);
        controller
            .state
            .lock()
            .unwrap()
            .sessions
            .get_mut(&session.id)
            .unwrap()
            .refreshed = Instant::now() - LASER_LEASE;
        controller.expire();
        assert!(session.events.has_changed().unwrap());
        let current = session.events.borrow_and_update().clone();
        assert_eq!(current.mode, PresentationMode::Mouse);
        assert_eq!(current.reason, Some("lease_expired"));
        assert_ne!(current.udp_token, encode_token(&old));
        controller.udp(peer, old, 88, 10, 10, 120);
        assert!(input.0.lock().unwrap().is_empty());
        controller
            .switch(
                session.id,
                PresentationMode::Laser,
                LaserSettings::default(),
            )
            .unwrap();
        controller.force_exit("tray");
        assert_eq!(session.events.borrow().reason, Some("tray"));
    }
    #[test]
    fn denied_switch_releases_the_requesters_buttons_without_closing_the_owner() {
        let (controller, input, laser, peer) = fixture();
        let owner = controller.register(peer);
        let requester = controller.register(peer);
        controller
            .switch(owner.id, PresentationMode::Laser, LaserSettings::default())
            .unwrap();
        controller
            .execute(
                requester.id,
                &Command::ButtonDown {
                    button: Button::Left,
                },
            )
            .unwrap();
        assert!(
            controller
                .switch(
                    requester.id,
                    PresentationMode::Laser,
                    LaserSettings::default()
                )
                .is_err()
        );
        assert_eq!(controller.state.lock().unwrap().owner, Some(owner.id));
        assert_eq!(laser.hides.load(Ordering::Relaxed), 0);
        assert_eq!(
            input.0.lock().unwrap().as_slice(),
            ["ButtonDown { button: Left }", "ButtonUp { button: Left }"]
        );
    }
    #[test]
    fn cleaning_one_connection_does_not_release_another_connections_hold() {
        let (controller, input, _, peer) = fixture();
        let a = controller.register(peer);
        let b = controller.register(peer);
        let down = Command::ButtonDown {
            button: Button::Left,
        };
        controller.execute(a.id, &down).unwrap();
        controller.execute(b.id, &down).unwrap();
        controller
            .execute(
                b.id,
                &Command::ButtonUp {
                    button: Button::Right,
                },
            )
            .unwrap();
        controller.disconnect(a.id);
        assert_eq!(input.0.lock().unwrap().len(), 1);
        controller.disconnect(b.id);
        assert_eq!(
            input.0.lock().unwrap().as_slice(),
            ["ButtonDown { button: Left }", "ButtonUp { button: Left }"]
        );
    }
}
