# Downloads10

Native Android Kotlin download manager targeting Android 7.0+ (API 24+) with support for armeabi-v7a and arm64-v8a devices.

## Features
- Real HTTP/HTTPS downloading using Android's native `HttpURLConnection`.
- HTTP Range resume when supported by the server.
- Pause/resume and automatic retry on network failures.
- 1–10 simultaneous downloads.
- Real downloaded bytes, total size when provided by the server, and live speed.
- Optional speed display.
- Persistent download queue/state.
- Background foreground-service operation.
- User-selected download folder through Android Storage Access Framework.
- Copy and update download URLs.
- Delete records only, or records plus downloaded files.
- Select all / individually selected downloads.
- Dark UI with light gray active cards and dark gray completed cards.

## Important server limitations
No download manager can bypass authentication, DRM, bot protection, expired signed URLs, or a server that refuses Range requests. If a server does not support resume, Downloads10 falls back to a full restart when necessary.

## Build
The repository includes a GitHub Actions workflow. It installs Gradle 8.9 and JDK 17 and builds both debug and release APKs.

For Termux, install a compatible JDK, Gradle, and Android SDK/platform tools, then run `gradle assembleDebug` from the project root.
