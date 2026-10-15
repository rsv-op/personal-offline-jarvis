# JARVIS — Build, Test, and Evaluation Guide

**Status date:** 2026-10-15
**Scope:** the deterministic offline assistant (rules + on-device intent brain), the encrypted
memory, optional local replies, and the Android build/CI that produces the installable APK.

## 1. Is it finished?

The agreed implementation scope is complete and build-validated on `main`: supported device
actions are deterministic and confirmation-gated, speech recognition runs locally through
Vosk, intent routing combines deterministic rules with an on-device neural classifier plus
the phrases you teach, memory is encrypted under a hardware-backed key, and CI builds,
tests and verifies an installable APK and publishes it to the `jarvis-latest` release.

That does **not** yet prove the app is production-ready. CI has no microphone, camera/flash,
alarm-clock app, imported speech model, imported reply model, or device TTS engine, and no
physical-device accuracy, latency, memory, battery, or reliability benchmark has been
recorded. Treat the app as **implemented and build-validated, awaiting on-device acceptance
testing**.

| Area | Current status |
|---|---|
| Android compilation, unit tests, debug APK | Passing in CI (12 test classes, 103 tests) |
| Offline speech recognition (Vosk) | Implemented; needs a user-imported model and a device test |
| Intent routing | Deterministic rules first, then the ai-matrix classifier (holdout 0.9286), then taught phrases |
| Flashlight, alarm, timer actions | Implemented; every device-changing action needs explicit confirmation |
| Device status (“how much battery is left”) | Implemented; read-only, answered without confirmation |
| Encrypted memory | Implemented (AES-256-GCM, Android Keystore/StrongBox-backed key) |
| Open-ended answers | Optional, via a user-imported LiteRT-LM model |
| Always-on wake word | Not implemented; interaction is push-to-talk |
| File deletion | Intentionally unavailable; no executor is wired and the manifest requests no file access |
| Signed release / Play Store readiness | Out of scope; the published APK is a debug build signed with a cached debug key |
| Accuracy and performance numbers | Not measured on hardware yet; see the evaluation plan below |

## 2. What the app does

The normal interaction is:

```text
Tap the orb (push-to-talk)
  -> capture 16 kHz mono PCM
  -> Vosk recognises it locally and emits partial/final text
  -> decision order:
       1. deterministic rules      (flashlight polarity, alarm time, timer duration, status, delete)
       2. the ai-matrix brain      (neural classifier, or a phrase you taught)
       3. the local reply model    (optional, open-ended questions only)
  -> device-changing intent: speak the confirmation prompt, then listen for yes/cancel
       -> Confirm: execute (torch, alarm-clock intent, timer) and log the action
       -> Cancel: nothing happens, the action is logged as cancelled
  -> reply out loud through Android TextToSpeech and show it on screen
```

Speech models and the reply model are provisioned by you and copied into app-private
storage; neither is bundled in Git or in the APK. The reply model receives no tools, so it
cannot perform actions — only `UnknownIntent` requests reach it.

### Main implementation areas

| Area | Source | Responsibility |
|---|---|---|
| Activity and permissions | `MainActivity.kt` | Runtime microphone/camera permissions, document pickers for model import |
| Audio capture | `stt/AudioCaptureSource.kt` | 16 kHz mono frames |
| Speech recognition | `stt/VoskSttEngine.kt`, `stt/OfflineSttIntegrator.kt` | Loads the local Vosk model, streams partial/final events, survives engine failures |
| Model storage | `stt/VoskModelStore.kt` | Validates and copies an imported `.zip` or folder into private storage |
| Deterministic routing | `nlp/IntentRouter.kt`, `nlp/TextNormalizer.kt`, `nlp/DurationParser.kt` | Polarity, alarm times, timer durations, status and delete patterns |
| Persona-independent intent entry point | `nlp/NlpProcessor.kt` | Merges rules + brain + taught phrases, applies the confirmation threshold |
| Intent brain | `aimatrix/AiMatrixFeaturizer.kt`, `AiMatrixClassifier.kt`, `OnDeviceBrain.kt` | Hashed character/word features, MLP forward pass, taught-phrase cosine match |
| Encrypted memory | `vault/VaultCipher.kt`, `vault/SecureVault.kt`, `vault/JarvisMemory.kt` | Keystore AES-256-GCM vault, taught phrases, preferences, action log |
| Orchestration | `orchestrator/AssistantViewModel.kt` | Voice loop, confirmation gate, execution, generation, memory writes |
| UI state | `statemachine/AssistantUiStateMachine.kt` | Lenient reducer for the visible states (also usable as a standalone state machine) |
| Device actions | `execution/IntentExecutors.kt`, `execution/ExecutionCoordinator.kt` | Torch, alarm-clock intent, timer, status report |
| Spoken output | `execution/TtsController.kt` | Wraps Android TextToSpeech (voice comes from the system) |
| Local replies | `generation/LiteRtLocalTextGenerator.kt`, `generation/LocalGenerationModelStore.kt` | Imports `.litertlm` files and runs short CPU-backed replies |
| Compose UI | `ui/AssistantScreen.kt` | Orb, status card, teach panel, confirmation card |
| Tests | `app/src/test/java/com/example/voiceassistant/` | NLP, brain, vault, state machine, view model, Compose screen, STT integrator |

## 3. Rules and calculations in the current implementation

### Audio frame sizing

- Sample rate **16,000 Hz**, mono, signed 16-bit PCM (`AudioCaptureSource.SAMPLE_RATE_HZ`).
- Frame size **1,600 samples** (`FRAME_SIZE`): `1600 / 16000 = 0.1 s` ≈ **100 ms**.
- One full frame is `1600 × 2 = 3,200 bytes` ≈ 3.13 KiB.
- The recorder buffer is `max(deviceMinimum, 1600 × 2 × 2)` — at least two frames (≈200 ms);
  individual reads may be shorter than a full frame.

### Deterministic routing and scores

`TextNormalizer` lowercases, replaces non-ASCII letters/digits and punctuation with spaces,
drops a small filler list (“uh”, “um”, “please”, “hey”), and collapses whitespace.

`IntentRouter` recognises, in order: flashlight/torch phrases (action off when the text
contains an off-word such as “off”, “kill”, “dim”, or explicitly on), timer phrases with a
duration, alarm phrases with a time, delete phrases, and status phrases. Everything else is
`UnknownIntent`. The confidences are fixed heuristics, **not calibrated probabilities**:

| Route | Score | Sensitivity |
|---|---:|---|
| Flashlight phrase with clear polarity | 1.0 | normal |
| Alarm phrase with an extracted time | 1.0 | normal |
| Alarm phrase without a time | 0.4 | normal |
| Timer phrase with a duration | 1.0 | normal |
| Timer phrase without a duration | 0.4 | normal |
| Delete phrase | 0.8 | high |
| Status phrase | 0.9 | read-only |
| Unknown phrase | 0.0 | — |

`NlpProcessor` accepts a rule match at or above **0.75**; below that it consults
`OnDeviceBrain`, which requires **0.40** confidence from the classifier or a **0.60** cosine
match against a phrase you taught. Every device-changing intent — including brain and taught
hits, and regardless of score — goes through the confirmation gate.

### Alarm times

`IntentRouter.extractAlarmTime` maps spoken number words ("one" … "twelve", "fifteen",
"thirty", "forty five") to digits, then matches `h`, `h:mm`, `h mm`, optionally followed by
`am`/`pm`. A time is returned only when it is a real clock time:

| Input | Result | Why |
|---|---|---|
| “wake me up at six thirty am” | `06:30 am` | normal case |
| “set alarm for seven” | `07:00` | minutes default to `00` |
| “alarm at 23 15” | `23:15` | 24-hour form |
| “alarm at 12 99”, “12:60” | `null` | minutes must be 0–59 |
| “alarm at 25 30” | `null` | hours must be 0–23 |
| “alarm at 13 pm”, “0 am” | `null` | 12-hour phrasing needs an hour of 1–12 |

A rejected time behaves exactly like a missing one: the assistant asks “What time would you
like the alarm for?” instead of forwarding a nonsense time. `AlarmExecutor.parseTime` then
converts the 12-hour form to 24-hour (`12 am` → 0, `12 pm` → 12, `1–11 pm` → `+12`), requires
`hourOfDay in 0..23 && minute in 0..59`, and hands `EXTRA_HOUR`/`EXTRA_MINUTES` to the system
alarm-clock intent. If no alarm app is installed, the action fails with a readable message
rather than pretending to succeed.

Timer durations come from `DurationParser` (e.g. “set a timer for ten minutes” → 600 seconds,
“1 hour and 30 minutes” via `describe(5400)`); a duration that cannot be parsed triggers
“How long should the timer run?”.

### The intent brain (ai-matrix)

`ai-matrix/` trains a small NumPy model; `AiMatrixFeaturizer.kt` reproduces the same features
on the phone. Both use **feature version 2** and **4,096 hash buckets**: word unigrams and
bigrams, plus 4-character prefixes/suffixes of longer words. The network is
`4096 → 32 (ReLU) → 7 (softmax)` with **131,335 parameters**, trained on **168 examples**
across the seven labels (`FLASHLIGHT_ON`, `FLASHLIGHT_OFF`, `ALARM_SET`, `TIMER_SET`,
`DELETE_FILE`, `DEVICE_STATUS`, `UNKNOWN`).

Measured on the laptop where it was trained: **1.000 training accuracy, 0.9286 held-out
accuracy** (`ai_matrix.golden` and `pytest` reproduce the numbers; CI runs the suite and the
export pipeline on every push). The exported asset is `app/src/main/assets/ai_matrix_model.json`
(≈670 KB), and `AiMatrixClassifier` rejects a model whose `formatVersion` is not 2 instead of
mis-routing with stale weights.

Safety properties: the classifier only chooses a **label**; every parameter (alarm time,
timer duration, file name) comes from the deterministic parsers. Brain and taught-phrase hits
are always treated as low-confidence and always confirmed.

### Encrypted memory

`VaultCipher` uses **AES-256-GCM** with a non-exportable key in the Android Keystore
(StrongBox when the device offers it, otherwise TEE-backed). `SecureVault` stores
`filesDir/vault/<name>.enc`, using the entry name as additional authenticated data, so a file
cannot be renamed into another slot. Stored items are the phrases you teach, preferences, and
an action log (executed, cancelled, failed). A `SoftwareVaultCipher` implements the same
interface on the JVM so the vault logic is unit-tested without a device. Losing the Keystore
key means losing the vault contents — that is the design, not a bug.

### Local reply settings

`LiteRtLocalTextGenerator` runs CPU inference with **4 threads**, a **2,048-token** context,
`topK = 40`, `topP = 0.9`, `temperature = 0.7`, accepts at most **2,000 characters** of input,
and caps replies at **192 tokens** and **900 characters**. The system instruction tells the
model to stay brief and never to claim it performed an action. These are code limits, not
measured optima; device speed is unknown until benchmarked.

### Model import limits

- Vosk archive: ≤ **2,000,000,000 bytes**, ≤ **5,000 entries**, model root searched to
  **6 levels**, must contain `conf/model.conf`; archives are extracted with zip-slip
  protection into private storage.
- Reply model: ≤ **4,000,000,000 bytes**, expected `.litertlm`, copied in 64 KiB chunks.
- These are import-size limits, not compatibility promises: the phone still needs the memory
  and the runtime has to support the file.

## 4. Toolchain and build instructions

- **JDK 21** to run the build; app bytecode stays **JVM 17**.
- **Gradle 8.14.6** (no wrapper is checked in; CI installs this version).
- Android SDK **Platform 36** with build-tools 36.0.0 (CI installs them with `sdkmanager`).
- App configuration: **minSdk 26**, **compileSdk/targetSdk 36**, AGP **8.13.0**, Kotlin
  Android/KAPT/Compose plugins **2.4.21**, Hilt **2.57.2**, LiteRT-LM Android **0.18.0**,
  Vosk Android **0.3.75**.
- Only phone ABIs are packaged (`arm64-v8a`, `armeabi-v7a`), so the APK does not install on
  x86 emulators by design.

```sh
java -version                                  # JDK 21
gradle --version                               # 8.14.6
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk   # optional, with a device attached
```

The Kotlin suite has **103 tests in 12 classes**, all of which run on the JVM:

- `NlpProcessorTest`, `IntentRouterTest`, `DurationParserTest` — routing, polarity, alarm
  times (including the rejected impossible times), timer durations, brain/taught fallbacks.
- `AiMatrixFeaturizerTest`, `AiMatrixClassifierTest`, `OnDeviceBrainTest` — features, forward
  pass, asset/version validation, taught-phrase behaviour.
- `SecureVaultTest`, `JarvisMemoryTest` — encryption round-trips, AAD binding, memory edits.
- `AssistantUiStateMachineTest`, `AssistantViewModelTest` — every state transition and the
  end-to-end voice loop with mocked STT/TTS/executors, including confirm, cancel and the
  follow-up questions.
- `AssistantScreenTest` — the Compose screen with Robolectric.
- `OfflineSttIntegratorTest` — partial/final events, empty audio, initialisation failure.

Reproduce the Python side (Python 3.10+, NumPy):

```sh
cd ai-matrix
python -m pip install -r requirements.txt
python -m pytest tests -q                # 35 tests
python -m ai_matrix.cli evaluate         # prints the held-out accuracy
python -m ai_matrix.cli export           # rewrites app/src/main/assets/ai_matrix_model.json
```

CI (`.github/workflows/build.yml`) runs the Python suite and the Gradle test + assemble task,
then **opens the built APK** and asserts it really is the offline assistant: package
`com.example.voiceassistant`, `minSdk 26 → targetSdk 36`, `arm64-v8a` native code, the
ai-matrix asset inside the archive, a valid signature, and **no `INTERNET` permission**. The
findings are published as an `APK verification` annotation, and any violation fails the build.
The debug keystore is cached so successive builds install over each other, and JUnit reports
are uploaded as the `junit-reports` artifact. Runs on `main`, tags, and manual dispatches also
publish the APK to the `jarvis-latest` release.

## 5. Install it on a phone

1. On the phone, open
   `https://github.com/rsv-op/personal-offline-jarvis/releases/latest` and download
   `jarvis-offline-debug.apk` (no release yet: Actions → newest green **Build** → artifact
   `personal-offline-jarvis-apk`).
2. Tap the file and allow installs from your browser when Android asks. The `versionCode` is
   the CI run number, so later builds upgrade in place; a build from a different signing key
   would need the old app uninstalled once.
3. Open **JARVIS** and grant microphone (speech) and camera (torch) permissions.
4. Import a speech model: download `vosk-model-small-en-us-0.15.zip` (~40 MB) from
   <https://alphacephei.com/vosk/models> and use **Import model .zip** — the app unzips and
   validates it itself. An already-extracted folder also works through **Import model folder**.
5. Optional: **Import local reply model** with a compatible `.litertlm` file for open-ended
   questions. Commands work without it; open-ended questions then get a short explanation.
6. For spoken replies to stay offline, install/select an **on-device** Android TTS voice
   (Settings → System → Languages & input → Text-to-speech).

Both models live in app-private storage; clearing app data or uninstalling requires importing
them again. The app requests no `INTERNET` permission, and the only permissions it declares
are `RECORD_AUDIO`, `CAMERA` and the alarm-clock intent permission.

## 6. Manual acceptance checklist

Record the phone model, Android version, Vosk model name/version, `.litertlm` identifier, TTS
voice, and whether the network was disabled.

| Check | Procedure | Expected result |
|---|---|---|
| First launch without models | Tap the orb before importing anything | Model status says it is missing; no action is taken |
| Import speech model | Import the Vosk zip (and an extracted folder on a second run) | Status becomes ready; an invalid archive shows an error and does not replace the installed model |
| No speech | Start listening, stay silent, stop | Returns to idle, reports no speech detected |
| Flashlight cancellation | Say “turn on flashlight”, choose **Cancel** | Prompt appears first; the torch never changes |
| Flashlight confirmation | Repeat and choose **Confirm**, then try “switch the torch off” | Torch changes only after confirmation; the action log records it |
| Spoken confirmation | Say “turn on the flashlight”, then answer “yes” (and “cancel” on a second run) | Spoken yes executes; spoken cancel does nothing |
| Alarm follow-up | Say “set an alarm” with no time | Assistant asks for a time; no alarm is created |
| Impossible alarm time | Answer “twelve ninety nine”, then “eleven thirty pm” | The impossible time is refused and asked again; the valid one is offered for confirmation |
| Timer | Say “set a timer for ten minutes” | Confirms, then starts the timer |
| Device status | Ask “how much battery is left” | Answered immediately, no confirmation |
| Delete request | Say “delete my notes file” | Explained as unavailable; nothing is deleted |
| Unknown request without a reply model | Ask an open question first | Assistant prompts for a local reply model; no executor runs |
| Local reply | Import a `.litertlm` and ask an open question | Reply is generated locally and never claims to have changed settings |
| Offline check | Disable the network, use imported models and an offline TTS voice | Recognition, actions and speech all still work |
| Permission/device failures | Deny the microphone, or test a phone with no flash/alarm app | Understandable error, app stays usable, no false success |

Do not use real file deletion as a test: deletion is explicitly unavailable.

## 7. How to measure “how well it works”

Make a small labelled test set before changing code, and repeat it after each change:

- **Word error rate (WER)** for recognition: `(substitutions + deletions + insertions) / reference words × 100 %`.
- **Intent accuracy:** correct routes including correct `UNKNOWN` results / labelled utterances × 100 %.
- **Task success:** fully completed end-to-end cases / attempted cases × 100 % — transcript,
  intent, confirmation, device effect and spoken feedback must all match.
- **Safety:** count of device actions executed without a confirmation. Target **zero**; also
  verify every cancel leaves the device untouched.
- **Latency:** end of speech → transcript/confirmation, and confirmation → action/TTS done.
  Report median and p95 across repeated runs, per phone and model.
- **Generation quality:** score a fixed prompt set for relevance, factuality, clarity and
  safety; note hallucinations. Fluent output is not proof of correctness.

A practical first set: 10–20 examples per supported action plus negative/open-ended prompts,
including boundary times (23:59, 00:00, 12:00 pm) and the rejected forms above. Keep ASR
errors separate from routing errors so it is clear which stage needs work.

| Field | Record |
|---|---|
| Date and app build (CI run number = versionCode) |  |
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

1. The Android app was organised as a single Kotlin/Compose module with separate speech,
   NLP, brain, vault, orchestration, execution, UI and test packages.
2. **Speech and actions:** push-to-talk capture → on-device Vosk → deterministic routing →
   confirmation-gated flashlight/alarm, with TTS and on-screen feedback. Large model files
   stayed out of Git and are imported by the user.
3. **Optional replies:** open-ended questions can use a user-imported LiteRT-LM model. Only
   `UnknownIntent` reaches generation, and the model has no device-action tools.
4. **Learned understanding:** `ai-matrix/` added a trained classifier with a documented
   export format, shipped as a versioned asset and reproduced by Kotlin unit tests; taught
   phrases were added as an offline way to extend routing without retraining.
5. **Encrypted memory:** taught phrases, preferences and the action log moved into a
   Keystore-backed AES-GCM vault.
6. **Toolchain:** the build settled on JDK 21, Gradle 8.14.6, AGP 8.13.0 and Kotlin 2.4.21
   (with `kotlin-metadata-jvm` on the KAPT classpath for Hilt), then moved to
   compileSdk/targetSdk 36 for Android 16 while keeping minSdk 26.
7. **CI hardening:** the workflow installs its own SDK, caches the debug keystore, verifies
   the built APK, republishes failures as annotations, and publishes the installable release.
8. **Current round:** the unit suite was executed for the first time end to end; that found
   and fixed real defects (parameterised alarm/timer intents that never reached the
   confirmation gate, a state machine that dropped intents from a resting state, vault IO
   pinned to a hard-coded dispatcher, and an STT integrator that could tear down its own
   scope). Alarm-time validation was tightened so impossible times are refused rather than
   forwarded.

## 9. Remaining work before calling it production-ready

1. Run the acceptance checklist on at least one target phone with real imported models and an
   offline TTS voice, and fill in the scorecard above.
2. Measure WER, intent/task success, latency, memory and battery on representative hardware,
   and publish the device/model details with the numbers.
3. Always-on wake word, multilingual speech, and destructive actions are **not** implemented;
   decide whether any of them is a product requirement.
4. Training currently happens on a laptop. Teaching the phone *new* phrases works offline
   (cosine match), but the network weights are not updated on-device.
5. The packaged APKs cover phone ABIs only, so emulator testing requires building the debug
   variant with x86 ABIs enabled.
6. The build still uses Gradle 8.14.6, which CI reports as out of date; a Gradle 9 migration
   is a separate, testable change.
7. The published artifact is a debug-signed APK. A signed release build (own keystore,
   shrinking, Play or sideload distribution) is not part of the current scope.

**Bottom line:** the code, the tests and the CI build are complete for the agreed scope, and
the shipped APK is verified to be the offline assistant it claims to be. It should not be
described as fully verified until the on-device checklist and measurements are done.
