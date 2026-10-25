# Nanquim

Open-source (MIT) raster painting app for Android tablets with a stylus.

## Repository layout

| Path | Contents |
|---|---|
| `app/` | Android application (Compose UI, navigation, command wiring, diagnostics) |
| `document/` | Pure Kotlin: document model and command registry (JVM-testable) |
| `formats/` | Pure Kotlin readers/writers that do not need the engine |
| `engine/` | C++17 native engine (render thread, EGL/GLES 3.1) + Kotlin JNI facade |
| `benchmarks/` | Macrobenchmark journeys |
| `tools/` | Developer scripts (license check) |

## Building

Requirements: JDK 17+, Android SDK (platform 36), NDK 28.2.13676358, CMake 3.22.1. For host C++ tests: CMake ≥ 3.22, Ninja and a C++17 compiler on `PATH`.

```bash
./gradlew assembleDebug
./gradlew test :engine:hostTest :app:lintProdDebug
```

## Diagnostics
Settings → long-press the app version opens the Diagnostics screen (device, GPU, displays, stylus, latency test, JSON export).

## Legal
MIT License (`LICENSE`). Not affiliated with any other app maker; see `NOTICE.md`. Third-party libraries and assets: `CREDITS.md`.
