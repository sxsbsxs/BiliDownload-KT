# BiliDownload-KT

一个使用 **Kotlin + Jetpack Compose** 编写的 Android Bilibili 视频解析、封面预览与下载项目。

> 本项目仅用于个人学习、研究 Android 网络请求与下载管理。请仅下载你有权保存的内容，并遵守 Bilibili 服务条款、著作权法律及当地法规。项目与哔哩哔哩官方无关。

## 功能

- 输入 Bilibili 视频 URL 或 BV 号解析视频信息。
- 封面预览支持 Coil GIF 解码器（资源本身必须是 GIF 才会动图播放）。
- 可单独把视频封面加入系统下载队列。
- 支持多 P 视频选择。
- 支持用户自行提供 Cookie；Cookie 使用 Android Keystore AES-GCM 加密后保存在应用私有偏好中。
- 通过 WBI 签名接口获取可用播放流。
- 获取到渐进式 MP4 时直接下载；DASH 视频和音频流分别下载。
- 使用 Android DownloadManager 在系统通知中显示下载进度。
- 下载目录：`Download/BiliDownload/`。

## 当前限制

- **DASH 音视频暂不在应用内自动合并**：当前版本将视频 `.m4s` 和音频 `.m4s` 分别保存。可在电脑上使用 FFmpeg 合并：

  ```bash
  ffmpeg -i "video.m4s" -i "audio.m4s" -c copy "output.mp4"
  ```

- B站接口、WBI 签名规则、Cookie 校验和清晰度权限可能变化；接口失败时需要更新 `BiliApi.kt`。
- B站视频详情接口的 `pic` 字段通常是静态封面图；动态封面/GIF 是否可用取决于接口实际返回的资源 URL。不能仅凭视频页面有动态预览就推断 `pic` 是 GIF。当前封面下载根据 URL 后缀选择扩展名，部分 CDN 不暴露真实格式时需要进一步用响应 Content-Type/文件魔数检测。
- 不保证所有视频、地区、账号或清晰度均可下载。大会员/登录/版权限制不会被绕过。
- 当前版本仍未包含自动更新、批量下载、断点续传 UI、字幕/弹幕下载、可靠的 DASH 自动合并和完整下载任务管理。系统 DownloadManager 负责下载通知及队列。

## 环境要求

- Android Studio（建议使用支持 AGP 8.7.3 的版本）
- JDK 17
- Android SDK 35
- Gradle 版本：8.9；Android Gradle Plugin：8.7.3；Kotlin：2.0.21。
- 当前源码包未附带 Gradle Wrapper 脚本/JAR。首次在本机命令行构建前，需要安装 Gradle 8.9 并执行 `gradle wrapper --gradle-version 8.9`，再使用 `./gradlew`。Android Studio 也可直接导入项目并完成 Gradle 同步。

## 构建

1. 解压项目。
2. 使用 Android Studio 打开 `BiliDownload-KT/`。
3. 等待 Gradle Sync。
4. 连接 Android 设备或启动模拟器。
5. 点击 Run。

命令行构建（推荐先生成 Wrapper；需本机安装 Gradle 8.9）：

```bash
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

GitHub Actions 会在推送到 `main`/`master`、创建 Pull Request 或手动触发时自动构建 Debug 与 Release APK，并将 APK 作为构建产物保存 14 天。当前 Release 构建未配置签名，因此产物是**未签名 APK**，不能直接作为正式上架包。

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Cookie 使用

1. 在你自己的浏览器登录 Bilibili。
2. 从浏览器开发者工具中复制你自己的 Cookie 请求头。
3. 粘贴到应用 Cookie 输入框，点击“解析视频”。
4. Cookie 在本机使用 Android Keystore 生成的 AES-GCM 密钥加密保存。
5. 点击“清除 Cookie”可删除本机保存的 Cookie。

**安全提醒：** Cookie 等同于账号凭据。不要把 Cookie 发给任何人，不要提交到 GitHub、截图或日志中。请勿使用他人 Cookie。

## 项目结构

```text
BiliDownload-KT/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/bilidownload/
│   │   │   ├── api/BiliApi.kt
│   │   │   ├── download/StreamDownloader.kt
│   │   │   ├── model/VideoInfo.kt
│   │   │   ├── security/CookieVault.kt
│   │   │   └── MainActivity.kt
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
└── LICENSE
```

## 开源协议

本项目采用 MIT License，详见 [LICENSE](LICENSE)。

## 免责声明

本项目按“现状”提供，不提供任何明示或暗示担保。使用者应自行确认下载行为合法且符合平台规则。作者不鼓励未经授权的传播、商业使用或规避付费/访问控制。

## Android 兼容性与安装说明

- 最低支持 Android 6.0（API 23）；Android 6.0 及以上版本可尝试安装。
- 项目没有限定 CPU ABI，也没有启用 ABI 分包；生成的是通用 APK，覆盖常见的 `arm64-v8a`、`armeabi-v7a` 设备以及 Android 模拟器架构（前提是依赖本身支持）。
- 不依赖摄像头、GPS 等可选硬件；Manifest 不会因缺少这些硬件而过滤设备。
- Debug APK 使用 Android Gradle Plugin 的 debug key 签名；CI 的 Release APK 也临时使用 debug key 签名，目的是让测试安装可用。正式发布前必须换成自己保管的正式签名密钥；不要把 debug key 当作正式应用签名。
- 如果手机仍提示“解析软件包时出现问题”或“应用未安装”，请确认下载的是完整 APK 而不是 ZIP、手机 Android 版本不低于 6.0，并卸载同包名但使用不同签名的旧版本后重试。

### 本地构建

需要安装 JDK 17 和 Android SDK 35。仓库中的 `gradlew` / `gradlew.bat` 会优先使用系统 Gradle；若没有，则下载并缓存官方 Gradle 8.9。

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Windows：

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleRelease
```

APK 输出位置：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

### GitHub Actions 自动构建

推送到 `main` / `master`、创建 Pull Request，或在 GitHub Actions 页面手动运行 `Android CI`，都会构建 Debug 和 Release APK。构建完成后，在该次 workflow run 的 **Artifacts** 区域下载 `BiliDownload-KT-Android-APKs`。
