# JARVIS — Build, Test, and Evaluation Guide

**Status date:** 2026-10-14

**Scope:** the Stage 1 deterministic offline assistant, Stage 2 optional local replies, and the Android build configuration needed to validate them.

## 1. Is it finished?

The agreed implementation scope is complete: supported device actions are deterministic and confirmation-gated, open-ended requests can optionally use an imported LiteRT-LM model, and the Android CI build is green. PR [#3](https://github.com/rsv-op/personal-offline-jarvis/pull/3) delivered the assistant stages; PR [#4](https://github.com/rsv-op/personal-offline-jarvis/pull/4) aligned the build toolchain. The latest PR build ran unit tests and assembled the debug APK successfully in [GitHub Actions run 38563072003](https://github.com/rsv-op/personal-offline-jarvis/actions/runs/38563072003).

That does **not** yet prove the app is production-ready or that it works well on every phone. The CI job does not have a microphone, camera/flash, Android alarm app, imported speech model, imported generation model, or a device's TTS engine. There has not yet been a recorded physical-device accuracy, latency, memory, battery, or reliability benchmark. Treat the app as **implemented and build-validated, but awaiting on-device acceptance testing**.

| Area | Current status |
|---|---|
| Android compilation and debug APK | Passed in CI |
| Automated tests | 17 JUnit/Robolectric/Compose tests pass in CI |
| Offline speech recognition | Implemented with Vosk; requires a user-imported model and device test |
| Flashlight and alarm actions | Implemented; require explicit confirmation and device test |
| Open-ended answers | Implemented through optional, user-imported LiteRT-LM; requires a compatible model and device test |
| Always-on wake word | Not implemented; interaction is push-to-talk |
| File deletion | Intentionally unsupported; no delete executor is wired |
| Signed release / Play Store readiness | Not part of the validated scope |
| Accuracy and performance numbers | Not measured yet; use the evaluation plan below |

## 2. What the app does

The app is a single-module Kotlin/Compose Android application. Its normal interaction is:

```text
Tap orb
  -> capture 16 kHz mono microphone audio
  -> Vosk recognizes audio locally and emits partial/final text
  -> normalize text and route supported intents deterministically
       -> flashlight/alarm: ask for confirmation -> execute only after Confirm
       -> unknown/open-ended request: use local LiteRT-LM if installed
  -> show status/transcript and speak through Android TextToSpeech
```

The Vosk model and LiteRT-LM model are provisioned separately by the user and copied into app-private storage. Neither model is bundled in the repository or APK. The reply generator receives no tools for controlling the device. If no reply model is installed, supported device commands still work; an open-ended request gets an import-model prompt.

### Main implementation areas

| Area | Source | Responsibility |
|---|---|---|
| Activity and permissions | `app/src/main/java/com/example/voiceassistant/MainActivity.kt` | Requests microphone/camera permissions and opens Android's document pickers for model import |
| Audio capture | `stt/AudioCaptureSource.kt` | Captures mono PCM at 16 kHz and sends short frames to recognition |
| Speech recognition | `stt/VoskSttEngine.kt`, `stt/OfflineSttIntegrator.kt` | Loads the local Vosk model and streams partial/final recognition events |
| Speech model storage | `stt/VoskModelStore.kt` | Copies and validates the imported Vosk model tree in private storage |
| Text understanding | `nlp/TextNormalizer.kt`, `nlp/IntentRouter.kt`, `nlp/NlpProcessor.kt` | Applies normalization and deterministic rules for the currently supported intents |
| Orchestration and UI state | `orchestrator/AssistantViewModel.kt`, `ui/AssistantScreen.kt` | Connects recognition, confirmation, execution, generation, and Compose UI |
| Device actions | `execution/IntentExecutors.kt` | Controls the camera torch or sends Android's alarm-clock intent |
| Spoken output | `execution/TtsController.kt` | Wraps Android TextToSpeech; the actual voice is supplied by Android |
| Local text generation | `generation/LiteRtLocalTextGenerator.kt`, `generation/LocalGenerationModelStore.kt` | Imports `.litertlm` files and runs short CPU-backed replies |
| Automated tests | `app/src/test/java/com/example/voiceassistant/` | Covers NLP, view-model flows, STT integration, and the Compose screen |

The project also contains `AssistantUiStateMachine.kt`; the live `AssistantViewModel` currently publishes its own `StateFlow` state directly. The state-machine helper is not the central event dispatcher for the running flow.

## 3. Rules and calculations in the current implementation

There is no custom model training or learned intent classifier in this project. Intent routing is rule-based. Vosk and LiteRT-LM perform their own model inference using user-provided model files; the app's settings below are configuration values, not benchmark-derived optima.

### Audio frame sizing

- Sample rate: **16,000 samples/second**, mono, signed 16-bit PCM.
- Nominal frame size: **1,600 samples**.
- Calculation: `1,600 / 16,000 = 0.1 seconds`, so a full frame represents about **100 ms**.
- Byte size of a full frame: `1,600 samples * 2 bytes/sample = 3,200 bytes` (about 3.13 KiB).
- The recorder buffer is at least `1,600 * 2 bytes * 2 = 6,400 bytes` (two full frames / about 200 ms), or the larger device-reported minimum. Individual reads can be shorter than a full frame.

### Intent normalization and confidence

`TextNormalizer` lowercases the transcript, replaces non-ASCII letters/digits and punctuation with spaces, removes a small fixed list of fillers (including “uh”, “um”, “please”, and “hey”), then collapses whitespace. This is a lightweight English-oriented normalizer, not a multilingual language model.

`IntentRouter` currently recognizes:

- Phrases containing `flashlight` or `torch`. The action is **off** if the normalized text contains `off`; otherwise it is **on**.
- Phrases containing `alarm` or `wake me up`. A small number-word map and a regular expression extract a time. Supported word mappings include one through twelve, fifteen, thirty, and forty five.
- Everything else becomes `UnknownIntent`.

The scores are fixed heuristics, **not calibrated probabilities**:

| Route | Current score |
|---|---:|
| Flashlight phrase | 1.0 |
| Alarm phrase with an extracted time | 1.0 |
| Alarm phrase without a time | 0.4 |
| Unknown phrase | 0.0 |

`NlpProcessor` has a confirmation threshold of **0.75**, but all recognized device-changing intents (flashlight, alarm, and the reserved delete intent) require confirmation regardless of their score. Missing alarm time triggers a follow-up question. Unknown/open-ended text does not go through device-action execution; the view model sends it to the local reply path instead.

**Known alarm-time limitation:** the parser checks that the hour is between 0 and 23, but it does not currently reject minute values outside 0–59. It also accepts only a small set of spoken number forms. Until this is tightened and tested, use ordinary valid clock times during acceptance testing and treat broader time phrasing as unsupported.

### Local generation settings

`LiteRtLocalTextGenerator` currently configures CPU inference with **4 threads**, a **2,048-token context limit**, `topK = 40`, `topP = 0.9`, and `temperature = 0.7`. It sends at most **2,000 characters** of user input, caps generated output at **192 tokens** and then **900 characters**, and uses a short instruction to keep replies concise and prevent the model from claiming it performed actions. These settings are code limits; device speed and model quality have not been measured.

### Model import limits

- Vosk folder import: at most **2,000,000,000 bytes** (about 1.86 GiB); model-root search is limited to six directory levels and requires `conf/model.conf`.
- LiteRT-LM file import: at most **4,000,000,000 bytes** (about 3.73 GiB); data is copied in 64 KiB chunks and the filename is expected to end in `.litertlm` when the storage provider reports a name.
- These are import-size limits, not a promise that every file within the limit is compatible with the installed runtime or the phone's available memory.

## 4. Toolchain and build instructions

### Requirements

- **JDK 21** for running the build.
- **Gradle 8.14.6**.
- **Android SDK Platform 34** installed and licensed; set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or open the project in Android Studio and point it at the SDK.
- The app is configured for **minimum API 26** and **target API 34**.
- A connected Android device or emulator is needed for the manual runtime checks below.

The project does not currently check in a Gradle wrapper, so the `gradle` command below must resolve to Gradle 8.14.6. The important build versions are AGP **8.13.0**, Kotlin Android/KAPT/Compose plugins **2.4.21**, Hilt **2.57.2**, and LiteRT-LM Android **0.18.0**. Java and Kotlin app bytecode targets remain **JVM 17**; JDK 21 is the build runtime, not the app bytecode target.

### Build and run automated tests

Run from the repository root:

```sh
java -version      # should report JDK 21
gradle --version   # should report Gradle 8.14.6
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

The test task has **17 tests across four test classes**:

- `NlpProcessorTest`: normalization, flashlight/alarm routing, missing alarm time, unknown intent.
- `AssistantViewModelTest`: action execution/confirmation/cancellation, alarm follow-up, and missing reply-model behavior.
- `OfflineSttIntegratorTest`: partial/final events, empty audio, initialization failure, and start-before-initialization.
- `AssistantScreenTest`: listening/transcript UI and confirmation buttons.

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs the same unit-test and debug-APK tasks. On success, it uploads the APK as the `personal-offline-jarvis-apk` workflow artifact; download it from that Actions run's Artifacts section. CI validates compilation and test behavior; it does not exercise real hardware or load external model binaries.

## 5. Prepare a device for offline use

1. Install and launch the debug APK. Grant **Microphone** permission for speech capture and **Camera** permission for flashlight control when Android asks. Alarm creation also depends on an alarm-clock app being available.
2. Obtain and extract an Android-compatible Vosk model. The [Vosk model catalog](https://alphacephei.com/vosk/models) is a starting point; the small English model is a practical baseline. In the app, choose **Import speech model folder** and select the extracted model folder or a parent folder containing it. The selected tree must include `conf/model.conf` within the supported search depth.
3. Optional: transfer a LiteRT-LM-compatible `.litertlm` file to the phone and choose **Import local reply model**. A Gemma 3 1B instruction-tuned LiteRT-LM model is a reference option; verify the exact model/runtime compatibility and device memory requirements before use.
4. For spoken replies to remain offline, install/select an **on-device** Android TextToSpeech voice in Android settings. The app uses Android's TTS service; an online system voice may require network access even though the app's recognition and generation paths are local.
5. Tap the orb to start listening and tap again to stop. Review the transcript/status. Confirm or cancel any proposed device action using the on-screen buttons.

Both model files are copied into the application's private files directory. They are not in Git, not in the APK, and will need to be imported again if app data is cleared or the app is uninstalled.

## 6. Manual acceptance checklist

Run these checks on a real target phone as well as, where useful, an emulator. Record the phone model, Android version, Vosk model name/version, `.litertlm` model identifier, selected TTS voice, and whether the network was disabled.

| Check | Procedure | Expected result |
|---|---|---|
| First launch without models | Launch before importing anything; tap the orb | Model status says not installed; listening does not start; no action is taken |
| Import speech model | Import an extracted Vosk directory with `conf/model.conf` | Status becomes ready; invalid folders show an error and do not replace the installed model |
| No speech | Start listening, remain silent, then stop | Returns to idle and reports no speech detected |
| Flashlight cancellation | Say “turn on flashlight”; choose Cancel | Confirmation is shown first; torch remains unchanged |
| Flashlight confirmation | Repeat and choose Confirm; then test “turn off flashlight” | Torch changes only after confirmation; test on a device with a working flash |
| Alarm follow-up | Say “set alarm” without a time | Assistant asks for a time; it does not create an alarm yet |
| Alarm cancellation and confirmation | Answer “six thirty am”; cancel once, then repeat and confirm | Cancel creates no alarm; Confirm sends the time to Android's alarm app |
| Unknown request without reply model | Ask an open question before importing LiteRT-LM | Assistant prompts for a local reply model; no device executor is called |
| Local reply | Import a compatible `.litertlm` and ask an open question | Reply is generated locally, spoken/displayed, and never claims it changed device settings |
| Offline check | Disable network, use imported models, and select offline TTS | Recognition and generation continue; spoken output works with the installed offline voice |
| Permission/device failures | Deny microphone permission or test a device without a flash/alarm app | Error/status is understandable; the app returns to a usable state and does not report false success |

Do not use real file deletion as a test: deletion is explicitly unavailable. Do not assume successful APK assembly proves microphone, camera, alarm, model loading, or TTS behavior on a particular device.

## 7. How to measure “how well it works”

The repository contains correctness tests but no published performance or accuracy measurements. For a useful evaluation, make a small labeled test set before changing code and repeat it after each change. Suggested metrics:

- **Word error rate (WER)** for speech recognition: `(substitutions + deletions + insertions) / reference word count × 100%`. Keep the exact spoken reference transcript for each recording.
- **Intent accuracy:** `correct routes (including correct UnknownIntent results) / total labeled utterances × 100%`. Include positive examples, paraphrases, negative examples, background noise, and different speakers.
- **Task success:** `completed end-to-end test cases / attempted test cases × 100%`. A command only counts as successful if the transcript, intent, confirmation, device effect, and feedback all match expectations.
- **Safety:** count any device action executed before a user confirmation. The acceptance target is **zero**. Also verify that every Cancel leaves the action unapplied.
- **Latency:** record time from the end of speech to transcript/confirmation, and from confirmation to action/TTS completion. Report median and 95th percentile across repeated runs, separately for each phone and model.
- **Generation quality:** score a fixed set of open-ended prompts for relevance, factuality, clarity, and safety; note hallucinations and unsupported current-fact claims. Record model identifier and settings. Do not treat fluent output as proof of correctness.

A practical first test set could contain 10–20 examples for each supported action plus negative/open-ended prompts. That is a suggested test plan, not a result already achieved. Include time phrases with valid hour/minute combinations and boundary tests; the current minute-range gap should be addressed before relying on alarm parsing broadly. Keep ASR errors separate from intent-routing errors so it is clear which stage needs work.

Use a scorecard per device/model combination so results remain comparable:

| Field | Record |
|---|---|
| Date and app build |  |
| Device and Android version |  |
| Vosk speech model |  |
| Reply model and TTS voice |  |
| Number of test cases |  |
| WER |  |
| Intent-route accuracy |  |
| End-to-end task success |  |
| Unconfirmed device actions (target: 0) |  |
| Latency (median / p95) |  |
| Notes and failure examples |  |

## 8. How the project reached this point

1. The Android implementation was organized as a single Kotlin/Compose app with separate speech, NLP, orchestration, execution, UI, and test packages.
2. **Stage 1** connected push-to-talk audio capture to on-device Vosk, deterministic intent handling, confirmation-gated flashlight/alarm actions, and UI/TTS feedback. Large speech-model assets were kept out of Git and imported by the user.
3. **Stage 2** added optional open-ended replies using a user-imported LiteRT-LM model. Only `UnknownIntent` goes to generation; supported actions never become model tool calls. The model has no device-action tools.
4. Build checks initially exposed dependency/toolchain mismatches: the older JDK/Kotlin setup could not handle dependency metadata/class-file versions. The build moved to JDK 21, Gradle 8.14.6, AGP 8.13.0, and Kotlin plugins 2.4.21 while preserving JVM 17 app bytecode.
5. Kotlin 2.4 required migrating the target configuration to the top-level `compilerOptions` DSL. Hilt's older metadata reader then needed `kotlin-metadata-jvm:2.4.21` on the KAPT classpath. A final Kotlin compile error was fixed by changing the `trimIndent()` system instruction from `const val` to `val`.
6. The resulting merged build passed the GitHub Actions unit-test and debug-APK workflow. That completes build validation; physical-device validation and quantitative performance evaluation remain the next proof points.

## 9. Remaining work before calling it production-ready

1. Run the on-device acceptance checklist on at least one target phone with real imported models and offline TTS.
2. Add tests and input validation for alarm minutes (0–59), AM/PM edge cases, and additional natural time formats.
3. Measure WER, intent/task success, false actions, latency, memory, and battery on representative hardware; publish the model/device details with the results.
4. Test model import and actual LiteRT-LM initialization with the intended model files; CI currently does not contain those large assets.
5. Decide whether always-on wake word, multilingual support, destructive actions, or a signed release are product requirements. They are not implemented/validated in the current scope.
6. Consider wiring `AssistantUiStateMachine` into the live ViewModel or removing the unused duplicate state-transition abstraction.

**Bottom line:** the code and CI build are complete for the agreed Stage 1/Stage 2 scope. The product should not be described as fully verified until real-device checks and measurements are completed.
