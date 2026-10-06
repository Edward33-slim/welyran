# Galaxy J7 Prime Crave Build

Target:

- Samsung Galaxy J7 Prime / SM-G610F
- Codename: on7xelte
- SoC: Samsung Exynos 7870
- Architecture: arm64-v8a
- ROM base: LineageOS 19.1 / Android 12L
- GApps: MindTheGapps sigma integrated into the ROM
- WebView: retained from the Android/LineageOS base
- Magisk: not included
- Slim profile: optional apps removed where safe
- Default system night mode: enabled

## Required GitHub Actions secrets

Create these repository secrets:

- CRAVE_USERNAME
- CRAVE_TOKEN

Optional:

- CRAVE_FLAGS

Do not commit crave.conf or any token.

## Build sequence

1. Start the J7 Prime - Start Crave Runner workflow.
2. Enter the temporary GitHub self-hosted runner token from Repository -> Settings -> Actions -> Runners -> New self-hosted runner.
3. After the runner becomes online, run Build J7 Prime ROM on Crave.
4. Keep BASE_PROJECT_ID=85 unless the current Crave project list says otherwise.
5. The build initializes LineageOS 19.1 directly and applies the Exynos 7870 local manifest.
6. The final ZIP size is measured after the first successful build. The target is under 1 GB, but this cannot be guaranteed before the actual build.

## Why Android 12L

The active Exynos7870-Revived-Beta trees explicitly support on7xelte with a LineageOS 19.1 / Android 12L build target. The device tree identifies the phone as Galaxy J7 Prime and targets ARM64.

## Important

This is a custom ROM. The build can use Samsung device/product identity strings, but it is not cryptographically Samsung-signed and must not be represented as an official Samsung firmware package.
