# 项目命令入口；Cargo 和 Gradle 仍负责实际编译。
set minimum-version := '1.56.0'
set default-list := true
set dotenv-load := true
set dotenv-filename := '.env.local'
set dotenv-override := true

[windows]
set shell := ['powershell.exe', '-NoLogo', '-NoProfile', '-Command']
[unix]
set shell := ['sh', '-eu', '-c']

_gradle := if os() == 'windows' { '.\gradlew.bat' } else { 'sh ./gradlew' }
_port := if os() == 'windows' { '$env:port' } else { '"$port"' }
_server_version := replace_regex(read('windows-server/Cargo.toml'), '(?s).*?\bversion\s*=\s*"([0-9A-Za-z.+-]+)".*', '$1')
_android_version := replace_regex(read('android-app/app/build.gradle.kts'), '(?s).*?\bversionName\s*=\s*"([0-9A-Za-z.+-]+)".*', '$1')
_version_argument := if os() == 'windows' { '$env:version' } else { '"$version"' }

# 同步两端版本号、递增 Android versionCode，并整理中英文更新记录；需要 Node.js。
version $version:
    node scripts/release.mjs version {{_version_argument}}

# 检查已提交、已推送的代码，创建并推送当前版本标签，触发 GitHub 自动发布。
release:
    node scripts/release.mjs release

# 编译并启动接收端；可用 just dev 8766 指定端口，Ctrl+C 停止。
[working-directory('windows-server')]
dev $port='8765':
    cargo run --locked -- --port {{_port}}

# 协议联调：启动接收端，但不注入鼠标和键盘事件。
[working-directory('windows-server')]
dev-dry-run $port='8765':
    cargo run --locked -- --dry-run --port {{_port}}

# 格式化 Rust 源码。
[working-directory('windows-server')]
fmt:
    cargo fmt

# 检查 Rust 格式。
[working-directory('windows-server')]
fmt-check:
    cargo fmt -- --check

# 运行 Rust 单元测试。
[working-directory('windows-server')]
test:
    cargo test --locked

# 运行 Rust Clippy。
[working-directory('windows-server')]
lint:
    cargo clippy --locked --all-targets -- -D warnings

# 检查 Rust 格式、测试和 Clippy；不需要 Android 环境。
check: fmt-check test lint

# 运行 Android JVM 单元测试，需要 JDK 和 Android SDK。
[working-directory('android-app')]
test-android:
    {{_gradle}} testDebugUnitTest

# 检查 Android API 兼容性和 Compose 用法，无需正式签名。
[working-directory('android-app')]
lint-android:
    {{_gradle}} lintDebug

# 同时检查 Rust 和 Android。
check-all: check test-android lint-android

# 构建 Windows Release 接收端，隐藏本机编译路径并复制到 dist/。
[windows]
[working-directory('windows-server')]
build-windows:
    & './scripts/build-release.ps1'
    $ErrorActionPreference = 'Stop'; New-Item -ItemType Directory -Force -Path '../dist' | Out-Null; Copy-Item -LiteralPath 'target/release/phone-mouse-server.exe' -Destination '../dist/phone-mouse-windows-{{_server_version}}.exe' -Force

# 构建 Android Debug APK 并复制到 dist/；用于开发测试。
build-android: _android-apk && _package-android

# 使用本地签名配置构建 Android Release APK 并复制到 dist/。
build-android-release: _android-release-apk && _package-android-release

# 构建 Windows 接收端和 Android Release APK；检查另用 just check-all。
[windows]
build: build-windows build-android-release

[private]
[working-directory('android-app')]
_android-apk:
    {{_gradle}} verifyDebugApk

[private]
[working-directory('android-app')]
_android-release-apk:
    {{_gradle}} verifyReleaseApk

[private]
[windows]
_package-android:
    $ErrorActionPreference = 'Stop'; New-Item -ItemType Directory -Force -Path 'dist' | Out-Null; Copy-Item -LiteralPath 'android-app/app/build/outputs/apk/debug/app-debug.apk' -Destination 'dist/phone-mouse-android-{{_android_version}}-debug.apk' -Force

[private]
[unix]
_package-android:
    mkdir -p dist
    cp android-app/app/build/outputs/apk/debug/app-debug.apk 'dist/phone-mouse-android-{{_android_version}}-debug.apk'

[private]
[windows]
_package-android-release:
    $ErrorActionPreference = 'Stop'; New-Item -ItemType Directory -Force -Path 'dist' | Out-Null; Copy-Item -LiteralPath 'android-app/app/build/outputs/apk/release/app-release.apk' -Destination 'dist/phone-mouse-android-{{_android_version}}.apk' -Force

[private]
[unix]
_package-android-release:
    mkdir -p dist
    cp android-app/app/build/outputs/apk/release/app-release.apk 'dist/phone-mouse-android-{{_android_version}}.apk'
