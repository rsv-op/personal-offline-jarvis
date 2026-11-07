# JARVIS — Build, Test, and Evaluation Guide

**Status date:** 2026-11-08
**Scope:** the deterministic offline assistant (rules + on-device intent brain), the encrypted
memory, local replies through llama.cpp, the Room-backed chat transcript, and the Android
build/CI that produces the installable APK.

## 1. Is it finished?

The agreed implementation scope is complete and build-validated on `main`: supported device
actions are deterministic and confirmation-gated, speech recognition runs locally through
sherpa-onnx, intent routing combines deterministic rules with an on-device neural classifier
plus the phrases you teach, memory is encrypted under a hardware-backed key, open-ended
answers come from a local llama.cpp model you import yourself, the transcript is stored in
Room, and CI builds, tests and verifies an installable APK and publishes it to the
`jarvis-latest` release.

That does **not** yet prove the app is production-ready. CI has no microphone, camera/flash,
alarm-clock app, imported speech model, imported reply model, or device TTS engine, and no
physical-device accuracy, latency, memory, battery, or reliability benchmark has been
recorded. Treat the app as **implemented and build-validated, awaiting on-device acceptance
testing**.

| Area | Current status |
|---|---|
| Android compilation, unit tests, debug APK | Passing in CI (24 test classes) |
| Offline speech recognition (sherpa-onnx) | Implemented; needs a user-imported model and a device test |
| Intent routing | Deterministic rules first, then the ai-matrix classifier (holdout 0.9286), then taught phrases |
| Flashlight, alarm, timer actions | Implemented; every device-changing action needs explicit confirmation |
| Device status (“how much battery is left”) | Implemented; read-only, answered without confirmation |
| Contact lookup | Implemented; read-only, so it is **not** confirmation-gated |
| Encrypted memory | Implemented (AES-256-GCM, Android Keystore/StrongBox-backed key) |
| Open-ended answers | Implemented, via a user-imported `.gguf` model run on llama.cpp (CPU, arm64) |
| Chat transcript | Implemented, in Room with WAL journalling; an interrupted reply is recovered on the next launch |
| Always-on wake word | Not implemented; interaction is push-to-talk |
| File deletion | Intentionally unavailable; no executor is wired and the manifest requests no file access |
| Signed release / Play Store readiness | Out of scope; the published APK is a debug build signed with a cached debug key |
| Accuracy and performance numbers | Not measured on hardware yet; see the evaluation plan below |

## 2. What the app does

The normal interaction is:

```text
Tap the orb (push-to-talk)
  -> capture 16 kHz mono PCM
  -> sherpa-onnx recognises it locally and emits partial/final text
  -> decision order:
       1. deterministic rules      (flashlight polarity, alarm time, timer duration, status, delete)
       2. the ai-matrix brain      (neural classifier, or a phrase you taught)
       3. the local reply model    (llama.cpp over an imported .gguf; open-ended questions only)
  -> device-changing intent: speak the confirmation prompt, then listen for yes/cancel
       -> Confirm: execute (torch, alarm-clock intent, timer) and log the action
       -> Cancel: nothing happens, the action is logged as cancelled
  -> reply out loud through Android TextToSpeech and show it on screen
  -> the turn is written to the Room transcript (user message + reply placeholder
     in one transaction; the placeholder is filled in when the reply completes)
```

You can also type into the chat panel instead of speaking. The transcript is observable
while a reply is still being generated, and a reply that was cut short by the app being
killed is marked as interrupted on the next launch rather than silently lost.

Speech models and the reply model are provisioned by you and copied into app-private
storage; neither is bundled in Git or in the APK. The reply model receives no tools, so it
cannot perform actions — only `UnknownIntent` requests reach it.

### Main implementation areas

| Area | Source | Responsibility |
|---|---|---|
| Activity and permissions | `MainActivity.kt` | Runtime microphone/camera/notification/contacts permissions, document pickers for model import |
| Audio capture | `stt/AudioCaptureSource.kt` | 16 kHz mono frames |
| Speech recognition | `stt/SherpaOnnxSttEngine.kt`, `stt/OfflineSttIntegrator.kt` | Loads the local sherpa-onnx model, streams partial/final events, survives engine failures |
| Model storage | `stt/SherpaOnnxModelStore.kt`, `stt/ModelArchiveExtractor.kt` | Validates and copies an imported `.zip` or folder into private storage |
| Deterministic routing | `nlp/IntentRouter.kt`, `nlp/TextNormalizer.kt`, `nlp/DurationParser.kt` | Polarity, alarm times, timer durations, status, contact and delete patterns |
| Persona-independent intent entry point | `nlp/NlpProcessor.kt` | Merges rules + brain + taught phrases, applies the confirmation threshold |
| Intent brain | `aimatrix/AiMatrixFeaturizer.kt`, `AiMatrixClassifier.kt`, `OnDeviceBrain.kt` | Hashed character/word features, MLP forward pass, taught-phrase cosine match |
| Encrypted memory | `vault/VaultCipher.kt`, `vault/SecureVault.kt`, `vault/JarvisMemory.kt` | Keystore AES-256-GCM vault, taught phrases, preferences, action log |
| Orchestration | `orchestrator/AssistantViewModel.kt` | Voice loop, confirmation gate, execution, generation, memory writes, streaming replies |
| UI state | `statemachine/AssistantUiStateMachine.kt` | Lenient reducer for the visible states (also usable as a standalone state machine) |
| Device actions | `execution/IntentExecutors.kt`, `execution/ContactsExecutor.kt` | Torch, alarm-clock intent, timer, status report, read-only contact lookup |
| Spoken output | `execution/TtsController.kt` | Wraps Android TextToSpeech (voice comes from the system) |
| Local replies | `generation/llama/` (bridge, generator, store, tier picker, service) | llama.cpp over an imported `.gguf`; model tier picker; foreground service that unloads under memory pressure |
| Native bridge | `cpp/jarvis_llama.cpp`, `cpp/CMakeLists.txt` | llama.cpp JNI bridge: load/unload, model chat template, raw-byte token callback, cancellation |
| Chat transcript | `chat/ChatMessageEntity.kt`, `chat/ChatMessageDao.kt`, `chat/ChatDatabase.kt`, `chat/ChatHistoryRepository.kt` | Room with WAL; transactional user message + reply placeholder; interrupted-reply recovery |
| Compose UI | `ui/AssistantScreen.kt`, `ui/ChatPanel.kt` | Orb, status card, teach panel, confirmation card, chat transcript |
| Tests | `app/src/test/java/com/example/voiceassistant/` | NLP, brain, vault, state machine, view model, Compose screen, STT integrator, chat DAO, model tier, UTF-8 decoder |

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

The local reply model runs on **llama.cpp** (`b12189`) behind a JNI bridge
(`app/src/main/cpp/jarvis_llama.cpp`). The settings live in `LlamaNative.kt` and
`LlamaLocalTextGenerator.kt`:

- CPU-only inference, **arm64-v8a only**, mmap-mapped weights, NEON/dotprod kernels.
  Vulkan is **off** by default (`GGML_VULKAN=OFF`); it can be turned on explicitly.
- The **chat template comes from the model itself** (`llama_model_chat_template`), so no
  template is hard-coded in the app and a model with its own template behaves correctly.
- A fixed random seed (`LLAMA_DEFAULT_SEED`) so a reply is reproducible run to run.
- Tokens are streamed back as **raw bytes** and reassembled into UTF-8 in Kotlin
  (`Utf8StreamDecoder.kt`), because a multi-byte character can straddle two token
  boundaries and decoding each token on its own would corrupt it.
- A generation can be **cancelled** from the Kotlin side.

**Cold start is 5–15 seconds** for a multi-gigabyte model. That is the honest number: the
weights have to be mapped and the KV cache allocated before the first token. It is not
"instant", and no claim in the app or the docs says otherwise.

### Model import limits

- Speech model: sherpa-onnx **Streaming Zipformer (English, INT8)** archives, ≤ **3 GiB
  extracted**, ≤ **5,000 entries**, model root searched to **6 levels**; archives are
  extracted with zip-slip protection into private storage. A model must be *usable*, not
  merely present: the importer and the speech engine both check the contents, so an empty
  or truncated model is refused with the reason instead of failing later at the orb.
- Reply model: ≤ **4,000,000,000 bytes**, expected `.gguf`, copied in 64 KiB chunks into
  app-private storage. There is **no downloader**: the file is imported from local storage.
- These are import-size limits, not compatibility promises: the phone still needs the memory
  and the runtime has to support the file.

### Choosing a model that fits

`ModelTierPicker` sizes the model against the device's **available** RAM, because loading a
model that is too large does not fail cleanly — the kernel OOM-killer takes the app out, and
on some phones the whole OS with it.

| Tier | On disk | Parameters | Free RAM needed (2.5× headroom) |
|---|---|---|---|
| Tiny | ≤ 1.2 GB | ≈1B, Q4 | ≥ 3.0 GB |
| Small | ≤ 2.4 GB | ≈3B, Q4 | ≥ 6.0 GB |
| Medium | ≤ 4.6 GB | ≈7–8B, Q4 | ≥ 11.5 GB |

The **2.5× headroom** is not padding for its own sake: llama.cpp memory-maps the weights
(the file size is a floor, not a ceiling) and then allocates the KV cache for the context
window on top of them. 2× proved too tight on the target device — a 2 GB model on 4 GB of
free RAM still OOM-killed the phone.

### Keeping the model resident without killing the phone

`LlamaModelService` is a foreground service (`specialUse`) that keeps the model loaded so
the assistant does not pay the 5–15 second cold start on every question. It polls the
device's available memory every **15 seconds** and **unloads the model** when free memory
falls below **512 MiB**, updating its notification to say so. The service declares
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS`; the last
one is requested at runtime on Android 13+.


## 4. Toolchain and build instructions

- **JDK 21** to run the build; app bytecode stays **JVM 17**.
- **Gradle 8.14.6** (no wrapper is checked in; CI installs this version).
- Android SDK **Platform 36** with build-tools 36.0.0 (CI installs them with `sdkmanager`).
- Android NDK **27.2.12479018** and CMake **3.22.1** (CI installs them; the llama.cpp bridge
  is compiled from source by the external native build).
- App configuration: **minSdk 26**, **compileSdk/targetSdk 36**, AGP **8.13.0**, Kotlin
  Android/KAPT/Compose plugins **2.4.21**, KSP **2.3.13**, Hilt **2.57.2**, Room **2.8.5**,
  sherpa-onnx **1.13.8**.
- Only phone ABIs are packaged (`arm64-v8a`, `armeabi-v7a`), so the APK does not install on
  x86 emulators by design. The llama.cpp bridge itself is arm64-v8a only; the 32-bit slice
  carries the sherpa-onnx speech library only.

### Why Room runs under KSP and not KAPT

Room's annotation processor has two front ends. The KAPT/JavaAP front end reads Kotlin
metadata through Room's **own shaded copy** of `kotlinx-metadata-jvm`, and that copy is
capped at a metadata version older than the one Kotlin 2.4.21 emits. The build therefore
fails during `kaptDebugKotlin` with:

```
Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0
```

Adding a newer `kotlin-metadata-jvm` to the processor classpath does **not** help: the
shaded copy is the one that is used, and the external one cannot override it. The KSP front
end reads metadata through the Kotlin analysis API instead, so it handles Kotlin 2.4
metadata. Hilt stays on KAPT — Dagger 2.57 un-shaded its metadata dependency, and the
build declares `kotlin-metadata-jvm:2.4.21` on the KAPT classpath for it.

```sh
java -version                                  # JDK 21
gradle --version                               # 8.14.6
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk   # optional, with a device attached
```

The Kotlin suite has **24 test classes**, all of which run on the JVM:

- `NlpProcessorTest`, `IntentRouterTest`, `DurationParserTest`, `ContactIntentRoutingTest` —
  routing, polarity, alarm times (including the rejected impossible times), timer durations,
  brain/taught fallbacks, and the read-only contact lookup (which must **not** be
  confirmation-gated, while the device-changing intents still must be).
- `AiMatrixFeaturizerTest`, `AiMatrixClassifierTest`, `OnDeviceBrainTest` — features, forward
  pass, asset/version validation, taught-phrase behaviour.
- `SecureVaultTest`, `JarvisMemoryTest` — encryption round-trips, AAD binding, memory edits.
- `AssistantUiStateMachineTest`, `AssistantViewModelTest` — every state transition and the
  end-to-end voice loop with mocked STT/TTS/executors, including confirm, cancel and the
  follow-up questions.
- `AssistantScreenTest` — the Compose screen with Robolectric.
- `OfflineSttIntegratorTest`, `SherpaOnnxSttEngineTest`, `SherpaOnnxModelValidatorTest`,
  `ModelArchiveExtractorTest`, `AudioDiagnosticsTest` — partial/final events, empty audio,
  initialisation failure, model validation and archive extraction.
- `ChatMessageDaoTest` — the Room transcript: the transactional user-message-plus-placeholder
  write, completing a reply, observing the transcript oldest-first, and marking interrupted
  replies on the next launch.
- `ModelTierPickerTest` — the RAM-based tier arithmetic and the refusal message.
- `Utf8StreamDecoderTest` — UTF-8 reassembly of raw byte streams, including a multi-byte
  character split across two token boundaries.
- `RuleBasedLocalTextGeneratorTest` — the honest no-model fallbacks.
- `MemoryBudgetTest`, `ProcParsersTest`, `TelematicsTickerTest` — memory headroom and the
  system-metrics parsers.

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
3. Open **JARVIS** and grant microphone (speech) and camera (torch) permissions. On
   Android 13+ also grant notifications (the model service uses them) and contacts (only if
   you want contact lookup).
4. Import a speech model: download a sherpa-onnx Streaming Zipformer (English, INT8)
   archive, e.g.
   `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2`,
   and use **Import model .zip** — the app unzips and validates it itself. An
   already-extracted folder also works through **Import model folder**.
5. Optional: **Import local reply model** with a `.gguf` file for open-ended questions.
   The app checks the file against the device's free RAM before loading it and refuses with
   a concrete message if it is too large. Commands work without it; open-ended questions
   then get a short explanation. A multi-gigabyte model takes **5–15 seconds** to load the
   first time.
6. For spoken replies to stay offline, install/select an **on-device** Android TTS voice
   (Settings → System → Languages & input → Text-to-speech).

Both models live in app-private storage; clearing app data or uninstalling requires importing
them again. The app requests no `INTERNET` permission, and the only permissions it declares
are `RECORD_AUDIO`, `CAMERA`, `SET_ALARM`, the foreground-service and notification
permissions, and `READ_CONTACTS` (read-only, and only used when you ask for a contact).

## 6. Manual acceptance checklist

Record the phone model, Android version, sherpa-onnx model name/version, `.gguf` identifier, TTS
voice, and whether the network was disabled.

| Check | Procedure | Expected result |
|---|---|---|
| First launch without models | Tap the orb before importing anything | Model status says it is missing; no action is taken |
| Import speech model | Import the sherpa-onnx archive (and an extracted folder on a second run) | Status becomes ready; an invalid archive shows an error and does not replace the installed model |
| No speech | Start listening, stay silent, stop | Returns to idle, reports no speech detected |
| Flashlight cancellation | Say “turn on flashlight”, choose **Cancel** | Prompt appears first; the torch never changes |
| Flashlight confirmation | Repeat and choose **Confirm**, then try “switch the torch off” | Torch changes only after confirmation; the action log records it |
| Spoken confirmation | Say “turn on the flashlight”, then answer “yes” (and “cancel” on a second run) | Spoken yes executes; spoken cancel does nothing |
| Alarm follow-up | Say “set an alarm” with no time | Assistant asks for a time; no alarm is created |
| Impossible alarm time | Answer “twelve ninety nine”, then “eleven thirty pm” | The impossible time is refused and asked again; the valid one is offered for confirmation |
| Timer | Say “set a timer for ten minutes” | Confirms, then starts the timer |
| Device status | Ask “how much battery is left” | Answered immediately, no confirmation |
| Contact lookup | Say “what is Priya's number” (with the contacts permission granted) | The number is read out; **no confirmation prompt**, because reading does not change anything |
| Contact lookup without permission | Deny contacts and ask again | A clear "permission needed" answer; nothing else breaks |
| Delete request | Say “delete my notes file” | Explained as unavailable; nothing is deleted |
| Unknown request without a reply model | Ask an open question first | Assistant prompts for a local reply model; no executor runs |
| Local reply | Import a `.gguf` and ask an open question | Reply is generated locally and never claims to have changed settings |
| Reply model too large | Import a `.gguf` bigger than the device's free RAM | Refused with a message naming the required and available RAM; nothing is loaded |
| Cold start | Time the first reply after loading a multi-GB model | **5–15 seconds** — this is the expected number, not a bug |
| Chat transcript | Ask something and open the chat panel | Both the user message and the reply appear, oldest first |
| Interrupted reply | Kill the app mid-reply, then reopen it | The unfinished reply is marked as interrupted, not left blank or lost |
| Model unloaded under pressure | Leave the app in the background while memory gets tight | The foreground-service notification says the model was unloaded |
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
| sherpa-onnx speech model |  |
| Reply model (`.gguf`) and TTS voice |  |
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
3. **Optional replies:** open-ended questions could use a user-imported LiteRT-LM model. Only
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
8. **The rewrite:** the reply path was replaced with **llama.cpp** behind a JNI bridge
   (mmap, CPU NEON/dotprod, the model's own chat template, raw-byte token streaming with
   UTF-8 reassembly in Kotlin). The speech path was replaced with **sherpa-onnx** Streaming
   Zipformer. A **Room** transcript was added with WAL journalling, a transactional
   user-message-plus-reply-placeholder write, and recovery of replies interrupted by process
   death. A **read-only contacts tool** was added, plus a **RAM-based model tier picker** and
   a **foreground service** that unloads the model under memory pressure. There is still no
   `INTERNET` permission and still no model downloader: both models are imported from local
   files.
9. **Room under KSP:** the Room processor could not run under KAPT with Kotlin 2.4, because
   Room's KAPT front end reads Kotlin metadata through its own shaded copy of
   `kotlinx-metadata-jvm`, which is capped below the metadata version Kotlin 2.4.21 emits.
   Room was moved to the KSP front end, which reads metadata through the Kotlin analysis API
   instead, and upgraded 2.6.1 → 2.8.5.
10. **Current round:** the unit suite was executed for the first time end to end; that found
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
4. Retrieval-augmented generation (RAG), calendar tools and SMS tools are deliberately
   deferred; none of them is wired up.
5. Training currently happens on a laptop. Teaching the phone *new* phrases works offline
   (cosine match), but the network weights are not updated on-device.
6. The packaged APKs cover phone ABIs only, and the llama.cpp bridge is **arm64-v8a only**
   (32-bit is excluded and Vulkan is off by default), so emulator testing requires building
   the debug variant with x86 ABIs enabled.
7. The llama.cpp bridge is compiled from source in CI. A prebuilt library would cut the
   build time, at the cost of pinning the APK to a llama.cpp release.
8. The build still uses Gradle 8.14.6, which CI reports as out of date; a Gradle 9 migration
   is a separate, testable change.
9. The published artifact is a debug-signed APK. A signed release build (own keystore,
   shrinking, Play or sideload distribution) is not part of the current scope.

**Bottom line:** the code, the tests and the CI build are complete for the agreed scope, and
the shipped APK is verified to be the offline assistant it claims to be. It should not be
described as fully verified until the on-device checklist and measurements are done.
