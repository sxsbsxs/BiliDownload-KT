# BiliDownload-KT

Kotlin + Jetpack Compose + embedded Python (Chaquopy) Android client for Bilibili.

> Independent third-party project; not affiliated with Bilibili. Only save content you are authorized to download. The app does not bypass membership, copyright, region, or access controls.

## Architecture

- **Kotlin / Compose:** Android UI, lifecycle, API client, encrypted cookie storage, download queue, files and notifications.
- **Python 3.11 / Chaquopy:** deterministic URL normalization and playurl response inspection helpers.
- **OkHttp:** network transport.
- **Android media APIs / FFmpeg integration point:** audio/video muxing. Device and codec compatibility must be tested.
- **Room migration target:** persistent task queue and history (current baseline uses existing local implementation).

Python is embedded in the APK. Users do not need Termux or a separate Python installation. If Python initialization fails, the Kotlin parser remains available as a fallback.

## Build

- JDK 17
- Android SDK 35
- Gradle 8.9
- AGP 8.7.3
- Kotlin 2.0.21
- Chaquopy Gradle plugin 17.2.0
- Python 3.11
- minSdk 24

Build locally:

```bash
./gradlew :app:assembleDebug
```

Run Python helper tests:

```bash
python -m unittest discover -s app/src/test/python -p 'test_*.py' -v
```

GitHub Actions runs Python unit tests before building and uploading the Debug APK artifact.

## Authentication and quality

Guest mode is the default. The app requests only resources the public endpoints make available. Login can expose additional resources or quality levels only when the account and the specific video are entitled to them. The quality list must reflect actual API tracks, not guessed labels.

The existing Bilibili App authorization handoff is retained as a best-effort official link handoff. Whether the installed Bilibili app accepts that link depends on its current Android App Links configuration. No app-private data is read, and opening the Bilibili app is not treated as proof of successful login. The returned login state must be validated by the Bilibili navigation endpoint.

## Privacy

Cookies are account credentials. Never commit them, print them to logs, or share them. Keep credentials in app-private storage protected by Android Keystore. Python helpers do not receive or persist cookies.

## Limitations

- Bilibili API behavior and quality availability can change.
- Guest access cannot unlock member-only or otherwise restricted streams.
- 4K/8K, HDR, Dolby Vision, and 60 fps depend on the video, account entitlement, region, and API response.
- DASH muxing compatibility varies by codec and Android device.
- Subtitle and danmaku availability depends on the source video.
- The CI APK build is not a substitute for real-device authorization and playback tests.

## License

See [LICENSE](LICENSE).
