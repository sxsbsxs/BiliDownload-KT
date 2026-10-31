# BiliDownload-KT

**Kotlin + Jetpack Compose** 版本的 Bilibili Android 视频工具。此分支以个人仓库 sxsbsxs/bilibilidl 为功能迁移参考，使用 Kotlin 重写 Android UI、API、下载与本地文件管理，不依赖 Python/Kivy 运行时。

> 本项目是独立第三方工具，与哔哩哔哩官方无关。仅保存你有权下载的内容，并遵守平台条款、著作权法律及当地法规。不会绕过会员、版权、地区或访问控制。

## 已迁移的功能

### 首页与解析
- 支持 BV 号、AV 号、B站完整视频链接，以及 b23.tv / bili2233.cn 短链接。
- 读取标题、UP 主、简介、封面、时长、播放/弹幕/点赞/投币/收藏/评论数量。
- 多 P 列表、单 P 选择、全部分 P 批量下载。
- 按播放接口实际返回的视频轨道构建画质列表，覆盖 360P 到 8K、HDR、杜比视界等接口可能提供的档位；不凭空生成画质选项。
- 解析后自动播放选项，以及官方 B站 Web 播放器 WebView。

### 下载
- 视频 + 音频输出 MP4；支持 DASH 分轨下载后调用 Android MediaExtractor / MediaMuxer 合并。
- 采用 .part 临时文件和 HTTP Range 尝试断点续传；主地址失败时尝试备用 CDN 地址。
- 下载进度、取消当前任务。
- 另外支持封面图片、弹幕 XML、字幕 SRT。
- 文件导出到公共目录 Download/bilibilidl/，并保留应用私有目录副本供历史列表使用。

### 登录与账号
- 登录入口申请 B站网页端授权票据，并尝试将授权链接直接交给已安装的 B站 App；本应用不显示登录二维码。
- 授权完成后仍会请求 B站 nav 接口校验登录态，再把 Cookie 使用 Android Keystore AES-GCM 加密保存在本机。
- 退出登录会清除本机保存的 Cookie。

### 其他页面
- 已下载：列出本地下载文件、大小和时间，可尝试调用系统应用打开。
- B站同步：观看历史、收藏夹、公开视频搜索；历史/收藏结果会写入本地缓存。
- 我的：账号 UID/昵称/头像、下载文件数量和占用空间、项目仓库入口。
- B站互动：点赞、投币、收藏到默认收藏夹、关注 UP 主（需要账号权限）。

## 重要限制与验证状态

- GitHub Actions 的 Android Debug APK 已成功构建；这只证明 Kotlin 项目能编译打包，不等于已经在真实手机上完成扫码授权、4K/HDR 播放流下载、DASH 合并和各种编码兼容性测试。
- B站 App 是否接管授权链接由其 Android App Links / Intent Filter 决定。若当前 B站版本没有接管该链接，应用会明确提示授权未完成；不会用“打开了 B站首页”冒充登录成功。
- 游客模式会直接尝试公开接口和公开资源；如果某个视频或画质要求登录、会员或其他权限，B站接口仍可能拒绝请求。应用不会破解或绕过这些限制。
- 画质菜单以 B站当前返回的实际轨道为准。视频本身、账号权限、地区、风控和接口策略都会影响可用画质；不能保证每个视频都有 1080P、4K、8K、HDR 或杜比视界。
- DASH 文件能否被 MediaExtractor 识别，以及 MediaMuxer 能否处理对应音视频编码、时间戳和容器，取决于 B站实际返回的流和 Android 设备解码/封装能力。合并失败时原始轨道会保留在应用下载目录。
- 字幕只在视频接口提供公开字幕轨道时可下载；弹幕 XML 也取决于视频是否有可用弹幕数据。
- 观看历史、收藏夹、点赞、投币、收藏和关注等账号功能需要有效 Cookie；公开视频解析和下载不会因为未登录而被应用预先拦截。
- 应用内 WebView 播放器使用 B站播放器页面。它不自动继承 B站官方 App 的私有登录态。

## 构建环境

- JDK 17
- Android SDK 35
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- Kotlin 2.0.21
- 最低 Android API 23

本地构建命令： ./gradlew :app:assembleDebug

APK 输出： app/build/outputs/apk/debug/app-debug.apk

GitHub Actions 会构建 Debug APK 并上传名为 BiliDownload-KT 的 Artifact。构建产物可在对应 Actions 运行页面的 Artifacts 区域下载。

## 项目结构

- app/src/main/java/com/example/bilidownload/api/BiliApi.kt
- app/src/main/java/com/example/bilidownload/api/BiliLoginManager.kt
- app/src/main/java/com/example/bilidownload/download/StreamDownloader.kt
- app/src/main/java/com/example/bilidownload/model/VideoInfo.kt
- app/src/main/java/com/example/bilidownload/security/CookieVault.kt
- app/src/main/java/com/example/bilidownload/BiliDownloadApp.kt
- app/src/main/java/com/example/bilidownload/MainActivity.kt
- app/src/main/res/xml/file_paths.xml

## 安全提醒

B站 Cookie 等同账号凭据。不要把 Cookie 发给他人、提交到 GitHub 或放进截图/日志。Cookie 只保存在应用私有数据中，并通过 Android Keystore 的 AES-GCM 密钥加密。若设备丢失或怀疑凭据泄露，请在 B站账号安全设置中退出其他设备/更新凭据。

## 开源协议

本项目采用 MIT License，详见 LICENSE。
