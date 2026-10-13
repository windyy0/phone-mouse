# 通信协议 v1

Android App 与 Windows 接收端使用相同协议版本。激光功能通过握手能力协商，普通鼠标和键盘功能保持兼容。

## 发现与握手

App 向 UDP `37655` 广播 `PHONE_MOUSE_DISCOVER`，接收端响应并每 2 秒广播一次：

```json
{"kind":"phone_mouse_server","protocol":1,"name":"我的电脑","port":8765}
```

App 连接 `ws://电脑IPv4:port/ws`，在 5 秒内发送：

```json
{"type":"hello","protocol":1}
```

成功后接收端返回：

```json
{"type":"ready","protocol":1,"version":"1.2.0","udp_port":8765,"udp_token":"00112233445566778899aabbccddeeff","features":["laser","laser_size","laser_trail_length","laser_range_300","shift_enter_v1","text_sync_v1","text_source_v1"]}
```

`features` 可省略。`laser` 表示支持激光模式，`laser_size`、`laser_trail_length` 和 `laser_range_300` 分别表示大小、最长拖尾长度及 300 上限能力。`text_sync_v1` 表示支持当前焦点输入框的文字同步，`text_source_v1` 表示支持普通输入时读取应用信息，`shift_enter_v1` 表示支持 Shift+Enter 快捷键。旧接收端缺少这些字段时，App 继续提供普通输入，设置页保留本地值并提示更新，发送时省略不支持的字段。

`udp_token` 是当前连接的输入标识。激光模式切换成功后会更新 token，旧 token 的延迟包会被丢弃。连接身份独立于来源 IP；同一台电脑上的多个连接互不覆盖。

握手失败返回 `error`（`code: "protocol"`）后关闭连接。协议不提供 TLS 或口令认证，请仅在可信局域网使用。

## WebSocket 指令

结构编辑的成功回执可另带 `edit_caret`，表示本次编辑完成后的 UTF-16 光标位置，仅与 `edit_normalized: true` 一起返回，普通读取省略。手机使用此位置合并等待期间继续输入的文字，并拒绝非法位置或拆分代理项。删除列表标记时，接收端先用 IAccessible2 的列表节点及完整原文确认结构，在正文起点退格，再精确回读取消列表后的内容和光标；手动输入的同形符号仍作为普通文字删除。

| 指令 | 示例 | 约束 |
| --- | --- | --- |
| move_pixels | `{"type":"move_pixels","dx":12,"dy":-5}` | 每轴整数 ±2000，单位为物理屏幕像素 |
| click | `{"type":"click","button":"middle"}` | `left` / `right` / `middle` |
| button_down / button_up | `{"type":"button_down","button":"left"}` | 按键需配对；断连自动释放 |
| scroll | `{"type":"scroll","delta":-120}` | 整数 ±2400，120 为一格 |
| touch_gesture | `{"type":"touch_gesture","horizontal":-120,"zoom":120}` | 整数 ±2400；`zoom` 为 120 的整数倍 |
| key | `{"type":"key","key":"enter"}` | `enter` / `backspace` |
| key (Shift+Enter) | `{"type":"key","key":"shift_enter"}` | 需接收端声明 `shift_enter_v1`；按下 Shift、按下/抬起 Enter、释放本次引入的 Shift |
| text | `{"type":"text","text":"你好😀\n","id":1}` | 1～2000 个 Unicode 字符 |
| text_read | `{"type":"text_read","id":5}` | 读取当前焦点输入框的文字同步状态 |
| text_source | `{"type":"text_source","id":8}` | 仅当接收端声明 `text_source_v1` 时使用；只读取当前前台程序、图标和窗口标题，不读取输入框文字或光标 |
| text_edit | `{"type":"text_edit","id":6,"target":1,"revision":3,"text":"你好啊","selection_start":3,"selection_end":3}` | 需匹配当前 `target` 和 `revision`；选区偏移按 UTF-16 code unit，不能拆分代理项 |
| text_enter | `{"type":"text_enter","id":7,"target":1,"revision":4}` | 需匹配当前目标和版本，在电脑输入 Enter |
| text_shift_enter | `{"type":"text_shift_enter","id":9,"target":1,"revision":4}` | 需 `shift_enter_v1`、当前目标和版本；返回 `text_state`，先完成文字及光标同步，再发送 Shift+Enter |
| ping | `{"type":"ping","id":2}` | 返回 `pong`，用于刷新激光租约 |
| presentation_set | `{"type":"presentation_set","id":3,"mode":"laser","settings":{...}}` | `mode` 为 `mouse` / `laser` |
| laser_settings | `{"type":"laser_settings","id":4,"settings":{...}}` | 仅激光拥有者可修改 |

手动普通输入使用 `text_source` 更新来源信息，通过 Win32 前台窗口和进程读取程序名、图标及标题，不查询 UIA / IAccessible2 文字或光标。回复沿用 `text_state`，`status` 为 `ordinary`，`target`、`revision` 为 0，`text` 为空，选区为 `null`；该回复只更新手机来源信息。没有 `text_source_v1` 能力时不发送此指令，普通键盘输入仍可使用。

激光设置字段：`color` 为 RGB 整数 `0`～`16777215`，`trail` 为布尔值，`fade_ms` 为 `100`～`3000`，`size_percent` 为 `50`～`300`，`trail_length_px` 为 `20`～`300`。后两个字段分别需要对应能力；没有 `laser_range_300` 时上限为 200。缺省值为大小 110%、长度 180 px。

成功的切换或设置返回 `presentation_state`：

```json
{"type":"presentation_state","id":3,"mode":"laser","udp_token":"ffeeddccbbaa99887766554433221100"}
```

`text_edit` 的成功回执可带 `edit_normalized: true`，表示适配器识别了编辑器自身的输入规则，并已再次精确回读转换后的文字。该标记仅用于本次回执，普通读取省略。目前识别富文本编辑器将行首 `- `、`* `、`+ ` 转换为 `• ` / `· `，以及空列表填充换行在输入正文后消失；其余文字必须保持一致。手机采用实际 `text` 和光标，以该回执版本继续编辑；等待期间独立的本地修改合并到转换后的正文，重叠修改仍保留并暂停。输入法选词期间延迟合并到本地提交之后，不重放已确认的列表前缀。

文字同步返回 `text_state`，包含 `target`、`revision`、`status`、`source`、`text` 和可选的 `selection_start` / `selection_end`、`source_icon`、`window_title`。`source` 为当前焦点控件所属应用的可执行文件名（去掉 `.exe`），不是窗口标题；WebView2 等嵌入式网页控件通过实际窗口层级关联外层宿主程序，名称和图标采用宿主应用，避免显示浏览器组件资料。`source_icon` 为程序图标的 PNG Base64（48×48 像素，最多 16,384 字符），取不到图标时省略。图标读取在电脑端缓存。`window_title` 为焦点控件所属窗口的真实标题（最多 512 个 UTF-16 code unit），每次读取时更新，取不到时省略；标题、程序名和图标变化不会增加文字同步版本。旧版本客户端可忽略这些可选字段。`status` 可为 `editable`、`read_only`、`password`、`no_input`、`unsupported`、`too_long`、`composing` 或 `unavailable`。写入后接收端会再次读取实际控件内容，未确认时返回错误；目标或版本不匹配时返回 `conflict`。密码控件只返回状态，不返回文字。

未检测到焦点输入框时返回 `no_input`，手机提示先选中电脑输入框，并继续轮询以便恢复同步。已识别为输入控件但没有可用文字模式时返回 `unsupported`，手机提示此输入框暂不支持同步并保留普通输入；控件读取失败时返回 `unavailable`，不将暂时失败判为不支持。浏览器只读页面根节点的导航地址不作为输入内容回传，此时返回 `no_input` 并保留来源应用资料。真实输入框中填写的 URL 正常回传。Windows 端通过适配器读取当前焦点控件：优先使用 UI Automation，无法提供可用文字模式时回退到 Win32 Edit/RichEdit。以上状态不禁止普通远程键盘输入；后续浏览器或应用桥接可以在不改变协议的情况下接入。

输入控件识别同时检查实际文字模式：除 `Edit` / `Document` 外，获得键盘焦点且支持可写文字的 `ComboBox` 搜索框也可同步；富文本编辑器报告 `Group` / `Custom` / `Pane` 类型时，需同时提供可写、可选择的文字范围和 `TextEdit` 模式。仅有容器类型、只读文字或没有焦点的父节点不视为输入框，避免漏掉谷歌搜索框和 QQ 的富文本编辑区，也避免回传整个页面或聊天记录。

长文本超过 500 个 UTF-16 单位或 5 个实际文本行时，同步光标所在段落及上一段、下一段，自动折行不产生新段落。跨段选区同时包含选中的段落，并各加一段相邻上下文。文档开头、结尾或辅助功能分页边界只包含实际可读取的相邻段落，空段落保留。可选字段 `paragraph_start` 是三段范围在系统接口所提供文字中的起点，`paragraph_number` 是光标所在段落（跨段选区时为首个选中段落）从 1 开始的段落号；文字和选区偏移均相对于本次返回的范围。接口提供分页文字时，`accessibility_page_start_line` 标明该页在文档中的首行号，此时 `paragraph_number` 仍使用文档真实行号，`paragraph_start` 相对于该页。写入仍使用 `target`、`revision` 和完整的范围文字，接收端只更新对应范围；版本包含可读取文字、选区以及分页位置的变化，不能覆盖系统接口未提供的内容变化。手机连续输入换行时，先确认完整修改，再在下一次普通读取时跟随新光标更新三段范围。范围极长时只传输光标附近不超过 16,000 单位的内容；手机输入区保持有限高度并在内部滚动，为光标下方的上下文留出可见空间。

wolai 等独立块编辑器可另外返回 `context_before` / `context_after`，分别为相邻输入块的只读预览（每项最多 1,000 个 UTF-16 单位）；字段缺失表示没有可读取的相邻文字块，空字符串表示真实空段落。手机显示上段、当前块及下段；`text`、选区和 `text_edit` 始终只包含当前块，预览变化不增加当前块的同步版本。CSS `::before` / `::after` 生成的占位提示经 IAccessible2 节点标签及 UIA 原文共同确认后作为空文字，用户输入相同字样仍作为正文。

文字同步只依赖 Windows 系统接口，无需编辑器扩展。UIA 负责读取文字和范围；IME 感知控件通过 IAccessible2 获取可用的文字及光标，避免轮询 UIA 的实时选词范围。手机主动移动光标时调用 UIA 选区接口，并通过可用的缓存光标确认；等待期间手机保留所选位置。完整短输入框只有光标变化时可更新写入版本，明确拒绝且原文未变化的光标竞争最多重新提交两次；真实内容、目标、长文范围或无法解释的版本变化仍暂停，未确认写入不重发。富文本编辑器优先替换差异范围，避免用 `SetValue` 重建整个编辑器。VS Code / Monaco 的 `native-edit-context` 和 `inputarea monaco-mouse-cursor-text` 只有在 UIA 与 IAccessible2 文字一致、光标有效、真实行号可定位且文字范围可编辑时才进入同步；空代理、提示文案或缺少可靠位置返回 `unsupported`，自动保留普通实时输入。分页写入只替换差异范围，禁止将该页通过 `SetValue` 当作全文写回，并确认实际选区及修改结果。接收端不安装扩展或自动启用编辑器辅助功能；VS Code 的 `editor.accessibilitySupport: auto` 可能不暴露内容。长文本无法获取可靠光标位置时返回 `too_long`，不传全文或猜测当前段落；后续可靠内容和光标恢复时自动恢复同步。降级状态仍返回来源程序、实际图标和窗口标题。普通 `text`、`key` 指令独立于同步状态，回车和退格继续可用。

QQ 空编辑框中标记为 `ProseMirror is-empty` 的单个尾部占位换行作为空正文，不加入手机编辑内容；真实换行仍保留。确认失败区分 `body_unconfirmed`（正文）与 `selection_unconfirmed`（光标）。首轮确认超时后只再次读取，同一目标仍可编辑且文字严格等于本次提交时确认成功，不重复注入文字。手机只读轮询或光标请求失败时保留原目标和手机输入，成功重新读取并验证目标、原文后才继续写入；读取失败不作为写入失败暂停输入法。调试日志记录阶段、耗时和文字长度，不记录正文。

Android 范围身份包括 `target`、`paragraph_start` 和 `accessibility_page_start_line`。分页快照即使内容较短、没有 `paragraph_start`，也不使用光标竞争重试，因为分页版本还包含文档身份和页位置。短文首次超过长文阈值时，先按本次提交的完整范围确认；下一次普通读取才裁剪到新段落。手机光标保留在选区回执后最多持续一秒，之后重新跟随电脑的实际选区。

UIA 的 `TextUnit_Character` 可能包含不产生文字的富文本结构位置，移动次数不能直接按 UTF-16 偏移设定上限。选区定位先测量能够覆盖目标文字偏移的原生上界，再二分查找具有精确文字前缀长度的边界；缺失的边界不会猜测，定位过程同样受请求截止时间限制。

原生 Edit/RichEdit 的跨进程读写、选区消息使用 `SendMessageTimeoutW`，单次最多等待 200 ms，并受本次请求截止时间限制；跨进程正文使用 `WM_SETTEXT`，而非 `SetWindowTextW`。文字 worker 的两秒截止时间包含排队、读取与写入确认；已过期的预检查不会继续执行写入。`composing` 留作能够可靠报告电脑输入法组合态的适配器状态；当前 Windows 适配器不主动查询实时选词范围。

错误返回 `error`，错误代码为 `protocol` / `format` / `input` / `rate` / `windows` / `presentation` / `text_sync`，有对应请求 id 时才携带 `id`。文字成功返回 `text_sent`。

激光模式是系统鼠标的视觉效果，鼠标移动、点击、拖动、滚轮、手势和键盘继续有效。同一时间只有一个连接可以拥有激光；拥有者每 2 秒发送 `ping`，超过 8 秒没有有效刷新时接收端关闭激光。托盘退出、租约失效、显示异常或断连会推送无 id 的 `presentation_state`，并恢复普通鼠标模式。

每帧最大 128 KiB，每个连接每秒最多 400 条 JSON 指令；WebSocket 心跳间隔 15 秒，超过 45 秒无入站消息时关闭连接。文字同步单次最多传输 16000 个 UTF-16 code unit。

## UDP 移动包

向 `ready.udp_port` 发送固定 40 字节数据，所有整数使用大端：

| 偏移 | 长度 | 内容 |
| --- | --- | --- |
| 0 | 4 | ASCII `PM01` |
| 4 | 16 | `udp_token` 解码后的字节 |
| 20 | 8 | 无符号序号，首包大于 0，同一 token 内严格递增 |
| 28 | 4 | 有符号 dx |
| 32 | 4 | 有符号 dy |
| 36 | 4 | 有符号 wheel |

标记、长度、token、来源 IP 或序号不正确的包会被丢弃；每个连接每秒最多接受 300 包。普通移动和竖向滚动使用 UDP，发送失败时回退到 WebSocket；拖动、按键、文字和双指手势始终使用有序 WebSocket。

使用与构建说明见[主 README](../README.md)。
