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
{"type":"ready","protocol":1,"version":"1.1.0","udp_port":8765,"udp_token":"00112233445566778899aabbccddeeff","features":["laser","laser_size","laser_trail_length","laser_range_300"]}
```

`features` 可省略。`laser` 表示支持激光模式，`laser_size`、`laser_trail_length` 和 `laser_range_300` 分别表示大小、最长拖尾长度及 300 上限能力。旧接收端缺少这些字段时，App 继续提供普通输入，设置页保留本地值并提示更新，发送时省略不支持的字段。

`udp_token` 是当前连接的输入标识。激光模式切换成功后会更新 token，旧 token 的延迟包会被丢弃。连接身份独立于来源 IP；同一台电脑上的多个连接互不覆盖。

握手失败返回 `error`（`code: "protocol"`）后关闭连接。协议不提供 TLS 或口令认证，请仅在可信局域网使用。

## WebSocket 指令

| 指令 | 示例 | 约束 |
| --- | --- | --- |
| move_pixels | `{"type":"move_pixels","dx":12,"dy":-5}` | 每轴整数 ±2000，单位为物理屏幕像素 |
| click | `{"type":"click","button":"middle"}` | `left` / `right` / `middle` |
| button_down / button_up | `{"type":"button_down","button":"left"}` | 按键需配对；断连自动释放 |
| scroll | `{"type":"scroll","delta":-120}` | 整数 ±2400，120 为一格 |
| touch_gesture | `{"type":"touch_gesture","horizontal":-120,"zoom":120}` | 整数 ±2400；`zoom` 为 120 的整数倍 |
| key | `{"type":"key","key":"enter"}` | `enter` / `backspace` |
| text | `{"type":"text","text":"你好😀\n","id":1}` | 1～2000 个 Unicode 字符 |
| ping | `{"type":"ping","id":2}` | 返回 `pong`，用于刷新激光租约 |
| presentation_set | `{"type":"presentation_set","id":3,"mode":"laser","settings":{...}}` | `mode` 为 `mouse` / `laser` |
| laser_settings | `{"type":"laser_settings","id":4,"settings":{...}}` | 仅激光拥有者可修改 |

激光设置字段：`color` 为 RGB 整数 `0`～`16777215`，`trail` 为布尔值，`fade_ms` 为 `100`～`3000`，`size_percent` 为 `50`～`300`，`trail_length_px` 为 `20`～`300`。后两个字段分别需要对应能力；没有 `laser_range_300` 时上限为 200。缺省值为大小 110%、长度 180 px。

成功的切换或设置返回 `presentation_state`：

```json
{"type":"presentation_state","id":3,"mode":"laser","udp_token":"ffeeddccbbaa99887766554433221100"}
```

错误返回 `error`，错误代码为 `protocol` / `format` / `input` / `rate` / `windows` / `presentation`，有对应请求 id 时才携带 `id`。文字成功返回 `text_sent`。

激光模式是系统鼠标的视觉效果，鼠标移动、点击、拖动、滚轮、手势和键盘继续有效。同一时间只有一个连接可以拥有激光；拥有者每 2 秒发送 `ping`，超过 8 秒没有有效刷新时接收端关闭激光。托盘退出、租约失效、显示异常或断连会推送无 id 的 `presentation_state`，并恢复普通鼠标模式。

每帧最大 16 KiB，每个连接每秒最多 400 条 JSON 指令；WebSocket 心跳间隔 15 秒，超过 45 秒无入站消息时关闭连接。

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
