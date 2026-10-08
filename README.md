# Aegis

A private Android browser: Kotlin + WebView for browsing, with an on-device
AI assistant powered by llama.cpp running natively (JNI) — no cloud, no
accounts, your history never leaves your phone.

## Features

- Arc-style dark minimal browser UI (tabs, address bar, command bar)
- Built-in Brave Search in-app results tab (Google/DDG/Bing as fallbacks)
- Offline AI chat: SmolLM2-360M-Instruct via llama.cpp JNI, runs fully on-device
- Floating AI entry from the new tab screen, plus command-bar access
- Native C++ AI engine (NDK r27, CMake) — arm64-v8a
- viewBinding, AppCompat, Material 3 theming

## Install

Grab the latest debug APK from the **Actions** tab:

1. Open the most recent **Android CI** run on GitHub.
2. Download the `aegis-debug-apk` artifact.
3. On your phone, enable **Install unknown apps** for your browser when prompted.
4. Open the downloaded APK and install.

### Build locally

1. Open the project in Android Studio (JDK 17, AGP 8.5.2, Gradle 8.7).
2. Let it sync; the NDK (r27) and CMake are pulled by the build files.
3. Run **app** on a device (arm64-v8a recommended) or emulator.
4. Or from the command line: `./gradlew assembleDebug` — the APK lands at
   `app/build/outputs/apk/debug/app-debug.apk`.

## Model packs

- **Lite (bundled flow):** SmolLM2-360M-Instruct Q4 (~218MB) downloads in-app
  the first time you open the AI panel, then runs fully offline.
- **Standard:** coming soon — a larger on-device model for better answers.

Model files are cached on-device; nothing is uploaded anywhere.

## Offline & privacy

- Browsing works with zero network calls home: no analytics, no trackers.
- AI inference runs 100% on-device via llama.cpp — conversations never leave
  the phone. The only network use is the one-time model download and your
  own web browsing.

## License

MIT — see LICENSE.
