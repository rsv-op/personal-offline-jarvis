# JARVIS — Local-First Android Assistant

A local-first Android voice assistant with a Compose UI, offline speech recognition, deterministic intent routing, confirmation-gated actions, and spoken/UI responses.

## Offline voice loop

The app now includes an end-to-end Vosk STT path:

1. Tap the orb and speak; the app captures 16 kHz mono audio from the microphone.
2. Vosk recognizes speech locally on-device.
3. The existing intent router handles supported flashlight and alarm requests. Sensitive or ambiguous actions require confirmation; a missing alarm time is collected as a follow-up.
4. Android TextToSpeech provides spoken feedback, and the transcript/status are shown in the UI.

The Vosk speech model is intentionally not bundled in Git or the APK because model files are large. To install one, obtain an Android-compatible Vosk model from the [Vosk model catalog](https://alphacephei.com/vosk/models), extract it on the device (or transfer it to the device), then tap **Import speech model folder** and select the model directory or a parent directory containing it. The selected tree must include `conf/model.conf`; it is copied into the app's private storage. Recognition itself does not make network requests. The current audio backend expects a 16 kHz model-compatible model; Vosk's small English model is a practical starting point.

The speech model and Android's TTS voice are separate. For spoken replies to remain offline, install/select an on-device TTS voice in Android settings. The app does not download either resource.

## Supported behavior and safety

- Flashlight on/off is routed to the existing Android action executor.
- Alarm requests use Android's alarm intent integration.
- Every device-changing action uses explicit confirm/cancel controls before it runs.
- `DeleteFile` remains intentionally unexecuted; the assistant explains that file deletion is unavailable rather than deleting data.
- The orb is a manual push-to-talk control. Always-on wake-word detection is not implemented.

## Project layout

- `app/src/main/java/com/example/voiceassistant/` — app entry points and Kotlin implementation.
- `app/src/test/java/com/example/voiceassistant/` — unit and Compose tests.
- `project_structure.md` — full file map.

## Build

Requirements: JDK 17, Android SDK 34, and Gradle 8.2.1.

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

The GitHub Actions workflow runs the same unit-test and debug-APK tasks.
