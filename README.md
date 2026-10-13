# 📱 手机键鼠

<p align="center">
  <strong>中文</strong>
  <span>&nbsp;·&nbsp;</span>
  <a href="README.en.md">English</a>
</p>

把 Android 手机变成 Windows 电脑的局域网触控板和键盘，支持触摸、文字输入和体感控制。

## 截图

<p>
  <img src="docs/images/phone-mouse-home.png" alt="主页与触控板" width="260">
  <img src="docs/images/phone-mouse-gyro.png" alt="体感鼠标与悬浮控制岛" width="260">
</p>

## ✨ 功能

- 📱 **手机代替鼠标键盘。**
- 🖱️ **触控板**
  - **单指**：轻点单击、双击按住拖动、长按拖动。
  - **双指**：轻点右键、双指滚动和缩放。
- ⌨️ **实时文字输入**、编辑后发送。
- 📝 **文字同步编辑**：Windows输入框内容和手机双向同步。
- 🖥️ **全屏触控板。**
- 🎯 **体感鼠标。**
- 🔴 **激光鼠标。** 光点跟随鼠标，保留原有操作。

## 🚀 使用

构建产物位于 `dist/`，构建方法见下文。

1. 在电脑上运行 `phone-mouse-windows-x.x.x.exe`，在手机上安装 `phone-mouse-android-x.x.x.apk`。
2. 让手机和电脑连接到同一局域网。
3. 打开 App，点击连接按钮，选择自动发现的电脑。

> 接收端首次启动可能请求管理员权限来保存局域网防火墙规则；接收端本身以普通权限运行。

## 🛠️ 从源码构建

准备以下工具，Rust 和 Just 需加入 `PATH`：

- **Rust 1.85+**，推荐使用 stable。
- [Just](https://just.systems/man/en/) 1.56+。
- 构建 Android 时还需要 **JDK 17+** 和 **Android SDK（API 36）**，可使用 Android Studio 配置。
- 版本与发布命令需要 **Node.js 20+** 和 **Git**，并加入 `PATH`。

将 [.env.example](.env.example) 复制为根目录的 `.env.local`，填写本机路径；已有环境变量时可省略。Just 自动读取该文件，它已被 Git 忽略。

```dotenv
JAVA_HOME="/path/to/jdk"
ANDROID_HOME="/path/to/android-sdk"
```

在项目根目录执行：

| 命令 | 用途 |
| --- | --- |
| `just dev` | 编译并启动 Windows 接收端 |
| `just check-all` | Rust 格式、测试、Clippy，以及 Android JVM 测试和 Lint |
| `just build-windows` | 打包 Windows Release EXE |
| `just build-android` | 打包 Android Debug APK，无需正式签名 |
| `just build-android-release` | 打包 Android Release APK，需要签名配置 |
| `just build` | 打包两端 Release 版本，需要 Windows 和签名配置 |
| `just version 1.0.1` | 同步版本号、锁文件及中英文更新记录 |
| `just release` | 检查已提交、已推送的代码，推送版本标签触发自动发布 |

所有打包产物写入 `dist/`，Debug APK 带 `-debug` 后缀。默认端口为 `8765`，可用 `just dev 8766` 更换；修改 Rust 代码后停止程序，再执行 `just dev`。

在 `.env.local` 中指定签名配置文件：

```dotenv
ANDROID_SIGNING_PROPERTIES="/path/to/signing.properties"
```

---

更多说明：[**通信协议**](protocol/README.md) · [**更新记录**](CHANGELOG.md) · [**发布与更新**](docs/release.md)。

## 📄 许可证

本项目采用 [MIT 许可证](LICENSE)。第三方依赖和 Gradle Wrapper 使用各自的许可证。
