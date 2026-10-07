# 通信协议 v1

Android App 与 Windows 接收端使用相同协议版本，更新时需同步更新两端。

## 发现与握手

App 向 UDP `37655` 广播 `PHONE_MOUSE_DISCOVER`，接收端响应并每 2 秒广播一次：

```json
{"kind":"phone_mouse_server","protocol":1,"name":"我的电脑","port":8765}
```

连接 `ws://电脑IPv4:port/ws`，在 5 秒内发送 UTF-8 JSON：

```json
{"type":"hello","protocol":1}
```

成功后接收端返回以下字段；`udp_token` 是每次连接生成的 32 位十六进制会话标识：

```json
{"type":"ready","protocol":1,"version":"1.0.0","udp_port":8765,"udp_token":"00112233445566778899aabbccddeeff"}
```

收到 `ready` 后才可发送输入。握手失败返回 `error`，`code` 为 `protocol`，随后关闭连接。当前不提供口令认证；会话标识仅用于校验 UDP 数据。

## WebSocket 指令

| 指令 | 示例 | 约束 |
| --- | --- | --- |
| move_pixels | `{"type":"move_pixels","dx":12,"dy":-5}` | 物理屏幕像素位移，每轴整数 ±2000；绕过 Windows 指针加速和 DPI 缩放 |
| click | `{"type":"click","button":"middle"}` | left / right / middle，一次按下并释放 |
| button_down / button_up | `{"type":"button_down","button":"left"}` | left / right / middle，按下与释放配对；断连自动释放仍按住的键 |
| scroll | `{"type":"scroll","delta":-120}` | 竖向滚轮，整数 ±2400，120 为一格 |
| touch_gesture | `{"type":"touch_gesture","horizontal":-120,"zoom":120}` | 整数 ±2400；横滚正值向右，缩放正值放大；zoom 必须为 120 的整数倍，通过 Ctrl＋滚轮执行 |
| key | `{"type":"key","key":"enter"}` | enter / backspace |
| text | `{"type":"text","text":"你好😀\n","id":1}` | 1～2000 个 Unicode 字符；允许换行和 Tab |
| ping | `{"type":"ping","id":2}` | 返回 `{"type":"pong","id":2}`；App 使用自己的单调时钟计算 RTT |

文字成功返回 `{"type":"text_sent","id":1}`；错误返回例如 `{"type":"error","code":"input","message":"…","id":1}`。错误代码为 protocol / format / input / rate / windows，有对应指令 id 时才携带 id。确认不代表目标应用已保存文字；未确认的文字保留在 App 中，不自动重发。

每帧最大 16 KiB，每个连接每秒最多 400 条 JSON 指令。WebSocket 心跳间隔 15 秒，超过 45 秒无入站消息时关闭连接。

## UDP 移动包

向 `ready.udp_port` 发送固定 40 字节数据，所有整数使用大端：

| 偏移 | 长度 | 内容 |
| --- | --- | --- |
| 0 | 4 | ASCII `PM01` |
| 4 | 16 | `udp_token` 解码后的字节 |
| 20 | 8 | 无符号序号，首包大于 0，同一会话内严格递增 |
| 28 | 4 | 有符号 dx，物理像素位移 |
| 32 | 4 | 有符号 dy，物理像素位移 |
| 36 | 4 | 有符号 wheel，竖向滚轮 |

会话标识或来源 IP 不匹配、序号不递增、长度或标记不正确的包会被丢弃；每个会话每秒最多接受 300 包，位移和滚动范围同 WebSocket 指令。

普通移动和竖向滚动使用 UDP；UDP 无法建立或发送异常时改走 WebSocket。拖动、按键、文字与双指手势始终走有序 WebSocket，避免按下、移动和释放乱序。

通信使用明文 WebSocket/UDP，没有 TLS 或口令认证，请仅在可信局域网使用。使用与构建说明见[主 README](../README.md)。
