# gfn-android

Independent Android GeForce NOW client research project.

> Personal research / authorized test environment. This project is not affiliated with or endorsed by NVIDIA. It does not modify subscription entitlements or account authorization.

## Current milestone

This baseline implements:

- multi-module Kotlin project structure;
- centralized Windows/GFN-PC protocol identity model;
- a testable session readiness/orchestration core;
- structured diagnostics models;
- streaming engine boundary prepared for WebRTC and direct MediaCodec output;
- a deterministic `protocol-cli` fixture harness;
- Android Compose shell using Material 3 Expressive theme, dynamic color, Home, Library, Diagnostics, and Settings screens.

It intentionally does **not** yet connect to real NVIDIA OAuth, CloudMatch, catalog, or WebRTC endpoints. Those are the next phase and will be implemented from verified protocol fixtures.

## Build requirements

- Android Studio Quail 2 (2026.1.2) or compatible
- JDK 17+
- Android SDK 37 (the app can still target Android 16 / API 36)
- AGP 9.3.0
- Gradle 9.5.0
- Kotlin 2.3.21 / AGP built-in Kotlin

The Android UI uses `androidx.compose.material3:material3:1.5.0-alpha25` so the project can use `MaterialExpressiveTheme` and `MotionScheme.expressive()`.

## Run the protocol fixture

```bash
./gradlew :protocol-cli:run
```

Expected lifecycle:

```text
create
queue 3
queue 1
preparing
ready confirmation 1
ready confirmation 2
ready
```

## Run Android

Open the project in Android Studio, install Android SDK 36, select the `app` configuration, and run on Android 10+.

## Development order

```text
Phase 0  Protocol fixtures / CLI
Phase 1  MD3E shell
Phase 2  OAuth + Catalog + Library + CloudMatch
Phase 3  H.264 SDR first frame/audio/input
Phase 4  HEVC Main SDR8
Phase 5  HEVC Main10 SDR10
Phase 6  HDR10 / BT.2020 / ST2084 / SurfaceView
```

See `docs/CLOUDNOW_REFERENCE.md` and `docs/ARCHITECTURE.md`.
