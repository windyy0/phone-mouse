# 发布与更新

## 快速发布

发布前确认仓库的 Actions Secrets 已配置 Android 签名，配置项见下文。

1. 在 `CHANGELOG.md` 的「未发布」中写好中文和英文更新说明。
2. 运行 `just version 1.0.1`：同步两端版本号、递增 Android `versionCode`、同步锁文件，将说明归入 `1.0.1`，保留空的「未发布」栏目。
3. 检查改动，提交并推送代码。
4. 运行 `just release`：检查通过后创建并推送 `v1.0.1` 标签，触发 GitHub 自动发布。

这两个命令需要 Node.js 和 Git。版本命令只修改四个版本与更新记录文件；发布命令要求工作区干净、当前分支提交已推送、版本与中英文说明完整，且版本高于远端已有稳定版本。不会自动提交代码或覆盖标签。推送失败可修复网络或权限后重跑，复用指向同一提交的本地标签。

只检查发布条件可运行 `node scripts/release.mjs release --dry-run`，不会创建或推送标签。

标签必须指向包含发布 workflow 和脚本的提交。普通代码推送只运行 CI；版本标签触发自动构建、检查和发布，无需手动上传安装包。

工作流先验证版本与更新说明，再运行两端测试、Lint / Clippy，构建签名 APK 和清理本机路径的 EXE，生成更新清单，最后上传并发布 Release。

**失败重试：** 在 Actions 的发布工作流中手动填写同一个已存在标签。构建或上传失败时不会发布残缺版本，已发布的 Release 不会被覆盖。

## 发布文件与更新说明

以 `v1.0.1` 为例，Release 包含：

- `phone-mouse-android-1.0.1.apk`
- `phone-mouse-windows-1.0.1.exe`
- `latest.json`

本地 `just build` 使用相同命名，Android Debug 包增加 `-debug` 后缀。

`CHANGELOG.md` 中每个版本使用以下格式，两种语言都必须有内容：

```markdown
## 1.0.1

### 中文

- 中文更新说明。

### English

- English release notes.
```

GitHub Release 展示中英文两段说明；`latest.json` 的 `notes` 使用中文正文，供客户端显示。

## Android 签名配置

在仓库 Settings → Secrets and variables → Actions 中配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | keystore 的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥密码 |
| `ANDROID_KEYSTORE_TYPE` | 可选，默认为 `PKCS12`；JKS 密钥库填写 `JKS` |

沿用已有 APK 的签名密钥，才能覆盖安装并保留设置。密钥与密码放在 Actions Secrets，保持 keystore 的安全备份。Windows EXE 当前未做 Windows 数字签名。

## 更新检查

- **Android：** 每天（默认，24 小时）、每周（7 天）或关闭；进入前台时按间隔检查，设置和最近检查时间会保存。关闭后仍可手动检查，手动检查和失败请求也计入间隔。新版本在未连接电脑时提示，通过浏览器下载 APK 后确认安装。
- **Windows：** 启动 15 秒后检查，此后每 6 小时检查；托盘菜单可手动检查。下载新 EXE 后退出旧接收端，再运行新文件。
- 自动检查失败保持安静，手动检查失败显示错误。Android 按 `versionCode`、Windows 按稳定版本号比较，更新均由用户选择。

两端读取固定名称的 `latest.json`，检查时不调用 GitHub REST API：

```text
https://github.com/windyy0/phone-mouse/releases/latest/download/latest.json
```

清单中的 SHA-256 用于核对发布产物；客户端通过浏览器下载，安装由用户完成。

**旧版本：** 已发布的旧 `v1.0.0` 没有更新检查，用户需手动安装首个带此功能的版本。首次发布新清单前，手动检查可能因缺少 `latest.json` 而失败。

**备用源：** 当前仅配置 GitHub，两端已保留备用源列表。以后接入镜像时，应同时镜像清单和安装包，调整清单中的下载链接与发布页地址，并将镜像清单地址加入两端列表。
