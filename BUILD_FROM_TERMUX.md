# Build from Termux

1. Put the project in `~/storage/downloads/Downloads10`.
2. Install JDK 17, Gradle and the Android SDK packages required for compileSdk 35.
3. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` to the Android SDK location and ensure `sdkmanager` is on PATH.
4. From the project directory run:

```bash
gradle assembleDebug
```

The debug APK is created at:

`app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions can build the project without a local Android SDK on the phone.
