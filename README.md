# JARVIS — Local-First Android Assistant

A local-first Android voice assistant with push-to-talk, on-device speech recognition, deterministic intent routing, confirmation-gated actions, optional on-device generative replies, and spoken/UI feedback.

## Offline voice loop

1. Tap the orb and speak; the app captures 16 kHz mono audio from the microphone.
2. Vosk recognizes speech locally on-device.
3. The deterministic intent router handles supported flashlight and alarm requests. Every device-changing action requires an explicit confirm/cancel choice; a missing alarm time is collected as a voice follow-up.
4. The assistant speaks the result through Android TextToSpeech and shows the transcript/status in the UI.

The Vosk speech model is intentionally not bundled in Git or the APK because model files are large. Obtain an Android-compatible model from the [Vosk model catalog](https://alphacephei.com/vosk/models), extract or transfer it to the device, then tap **Import speech model folder** and select the model directory or a parent directory containing it. The selected tree must include `conf/model.conf`; it is copied into the app's private storage. Recognition itself makes no network requests. The 16 kHz small English model is a practical starting point.

## On-device generative replies

Open-ended utterances that do not match a supported deterministic intent can be answered by LiteRT-LM on the device's CPU. The generative model is conversational only: it receives no action tools, cannot route or execute device commands, and is never used in place of the confirmation flow.

The reply model is also user-provisioned rather than bundled. Transfer a compatible `.litertlm` model file to the device and tap **Import local reply model**. A Gemma 3 1B instruction-tuned LiteRT-LM model is a reference starting point; model size and speed depend on the file and device. The imported file is copied to private app storage, and inference makes no network requests. If no local reply model is installed, recognized device commands still work and open-ended requests receive an install-model prompt.

The speech model, generative model, and Android TTS voice are separate. For spoken replies to remain offline, install/select an on-device TTS voice in Android settings. The app does not download these resources.

## Supported behavior and safety

- Flashlight on/off is routed to the Android action executor.
- Alarm requests use Android's alarm intent integration.
- All device-changing actions require explicit confirmation.
- `DeleteFile` remains intentionally unexecuted; the assistant explains that file deletion is unavailable rather than deleting data.
- The orb is a manual push-to-talk control. Always-on wake-word detection is not implemented.
- The generative model handles only open-ended conversation; it cannot operate the device.

## Project layout

- `app/src/main/java/com/example/voiceassistant/` — app entry points and Kotlin implementation.
- `app/src/test/java/com/example/voiceassistant/` — unit and Compose tests.
- `project_structure.md` — full file map.

## Build

Requirements: JDK 21, Android SDK 34, and Gradle 8.14.6. The Android/Kotlin bytecode targets remain Java 17; JDK 21 is required for the LiteRT-LM build-time tooling.

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

The GitHub Actions workflow runs the same unit-test and debug-APK tasks and uploads the resulting APK as a downloadable workflow artifact.

## Developer and verification guide

See the [Build, Test, and Evaluation Guide](docs/BUILD_AND_VALIDATION_GUIDE.md) for the implementation overview, configuration calculations, step-by-step build/model setup, automated and on-device test checklists, current limitations, and what remains before production readiness. [Download the Word document](docs/JARVIS_Build_Test_Evaluation_Guide.docx).
