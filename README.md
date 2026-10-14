# JARVIS — Local-First Android Assistant

A local-first Android voice-assistant foundation with Compose UI, a small intent parser, text-to-speech support, and an offline speech-recognition integration interface.

## Project layout

- `app/src/main/java/com/example/voiceassistant/` — app entry points and Kotlin implementation.
- `app/src/test/java/com/example/voiceassistant/` — unit and Compose tests.
- `project_structure.md` — full file map.

The STT module defines an engine interface; a native/offline engine implementation and model assets must be supplied separately. The `DeleteFile` intent is represented for routing/confirmation, but this starter does not perform file deletion.

## Build

Requirements: JDK 17, Android SDK 34, and Gradle 8.2.1.

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

The GitHub Actions workflow runs the same unit-test and debug-APK tasks.
