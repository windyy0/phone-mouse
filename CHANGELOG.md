# 更新记录 / Changelog

## 未发布

### 中文

### English

## 1.2.1

### 中文

- 修复同步输入时手机软键盘回车未正确传递到电脑的问题，支持连续回车、Shift+Enter 和换行后继续输入。
- 修复首次进入 Codex 等输入框时首个字词可能丢失，以及输入法确认候选词时被旧同步结果覆盖的问题。
- 修复 wolai 等分块编辑器退格后因输入控件重建而误报“电脑内容或输入位置已变化”的问题，改善换行与光标刷新时的同步稳定性。
- 改进 QQ 聊天输入框和窗口焦点的识别，提高暂未识别到输入框后的自动恢复能力。

### English

- Fixed forwarding of soft-keyboard Enter during synchronized input, including consecutive Enter presses, Shift+Enter, and continued typing after a line break.
- Fixed loss of the first word when entering fields such as Codex, and prevented stale synchronization replies from overwriting newly committed IME text.
- Fixed false content-or-caret conflicts after Backspace recreates an editor control in block editors such as wolai, and improved synchronization during line breaks and caret refreshes.
- Improved recognition of QQ chat editors and window focus, with better automatic recovery after an input field is temporarily unavailable.

## 1.2.0

### 中文

- 优化全屏触控板：统一说明、工具和设置弹窗，改善横屏、短屏、键盘弹出及边缘点击时的布局和关闭体验。
- 体感鼠标支持横屏、竖屏独立保存动作、左右反向和上下反向设置，并兼容原有共用设置；新增动作演示、说明入口和对应图标。
- 统一体感指向文案、手机朝向标记和动作演示样式，整理指针加速设置布局并修复设置范围边界。
- 新增激光鼠标：光点跟随系统鼠标，支持颜色、大小、拖尾、淡出和最长拖尾长度设置，保留原有鼠标、手势和键盘操作，并兼容旧接收端。
- 优化激光切换与输入安全：增加模式确认、会话所有权、心跳租约、UDP token 更新和异常恢复；Windows 使用原生 Alpha 光标改善遮挡与拖尾显示，并增加托盘退出演示模式。
- 新增文字同步编辑，可在手机上双向同步并编辑 Windows 当前输入框内容。
- 支持手动切换文字同步与普通输入。
- 改善长文本、富文本和 Markdown 列表的同步兼容性与输入稳定性。
- 新增 Shift+Enter 快捷键。
- 修复手机删除生成列表符号后无法继续同步的问题。
- 修复列表换行后输入区域上方出现多余空白的问题。

### English

- Improved the fullscreen touchpad with shared help, tool and settings dialogs, including better layout and dismissal behavior in landscape, short windows, keyboard-visible states and edge taps.
- Added independent portrait and landscape gyroscope actions, horizontal and vertical reversal settings, and migration from the original shared settings; added action previews, help and matching icons.
- Unified gyroscope direction wording, phone-orientation markers and action previews, while reorganizing pointer-acceleration settings and fixing range boundaries.
- Added a laser cursor with color, size, trail, fade and maximum trail length settings. Existing mouse, gesture and keyboard controls remain available, with compatibility for older receivers.
- Hardened laser switching and input safety with mode acknowledgements, per-session ownership, heartbeat leases, UDP token rotation and abnormal-disconnect recovery. The Windows receiver now uses a native alpha cursor for clearer trails and provides a tray action to exit presentation mode.
- Added bidirectional text synchronization and editing for the focused Windows input field from the phone.
- Added manual switching between synchronized text editing and standard input.
- Improved sync compatibility and input stability for long text, rich text, and Markdown lists.
- Added a Shift+Enter shortcut.
- Fixed synchronization after deleting generated list markers from the phone.
- Fixed extra space above the input text after a list line break.

## 1.1.0

### 中文

- Android 支持中英文界面，可手动切换或跟随系统语言。
- Android 新增「关于与更新」页面，支持手动检查及每天、每周或关闭自动检查，默认每天；项目地址可复制或打开。
- Windows 接收端增加新版本提示和托盘「检查更新」入口。
- 新增 CI 和标签触发的自动发布，支持 `just` 一键更新版本与发布标签，提供签名 APK、Windows EXE、更新清单和中英文说明。

### English

- Added Chinese and English UI support on Android, with manual switching or system language selection.
- Added an About & updates page on Android with manual checks, Daily (default), Weekly, and Off options, plus a project link to copy or open.
- Added new-version notifications and a Check for updates tray menu item to the Windows receiver.
- Added CI and tag-triggered releases, with `just` commands for versioning and tagging, signed APKs, Windows EXEs, update manifests, and bilingual notes.

## 1.0.0

### 中文

- 首次发布，支持通过局域网使用 Android 手机控制 Windows 电脑的鼠标和键盘。

### English

- Initial release: use an Android phone to control a Windows computer's mouse and keyboard over a local network.
