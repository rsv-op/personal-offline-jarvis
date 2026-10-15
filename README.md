# JARVIS — a fully offline Android voice assistant

A single Android app (package `com.example.voiceassistant`, label **JARVIS**) that listens,
understands, confirms and acts — entirely on your phone. Speech recognition, the intent
brain and the encrypted memory all run locally. **The app does not hold the `INTERNET`
permission at all**, so it cannot send your voice anywhere even if it tried.

| Piece | What it does | Runs on |
| --- | --- | --- |
| Vosk (`stt/`) | Speech → text, 16 kHz mono, no network | Your phone |
| ai-matrix (`ai-matrix/`, `aimatrix/`) | Phrase → intent, a 131k-parameter neural net | Trained on a laptop, **runs** on your phone |
| Rules (`nlp/`) | Alarm times, timer durations, on/off polarity | Your phone |
| Vault (`vault/`) | Taught phrases + preferences, AES-GCM under a StrongBox/TEE key | Your phone |
| Android `TextToSpeech` | Replies out loud (install an offline voice pack) | Your phone |
| LiteRT-LM (`generation/`) | *Optional* open-ended answers from a `.litertlm` model you import | Your phone |

## 1. Get it onto your phone

Everything below happens on the phone; you never need a computer.

1. Open **`https://github.com/rsv-op/personal-offline-jarvis/releases/latest`** in your
   phone's browser and download `jarvis-offline-debug.apk`.
   *No release yet?* Open the **Actions** tab → newest green **Build** → *Artifacts* →
   `personal-offline-jarvis-apk`. (Both are the same file.)
2. Tap the downloaded file. Android will ask to allow installs from this source — accept
   it (Settings → Apps → Special access → Install unknown apps → your browser).
3. If Android marks the APK as unsigned, it is only ever the *first*, not the last, build:
   the signed debug APK installs over itself as long as you keep installing from this
   repository.
4. Open **JARVIS** and grant the microphone and camera permissions (microphone for
   speech, camera for the flashlight torch).

## 2. Give it a voice (one-time, ~40 MB)

The speech model is intentionally **not** bundled — it is large and licensed separately.

1. On the phone, open <https://alphacephei.com/vosk/models> and download
   **`vosk-model-small-en-us-0.15.zip`** (about 40 MB).
2. In JARVIS tap **Import model .zip** and pick that file. The app unzips it itself
   (Android's Files app cannot), validates it, and copies it into private storage, so
   nothing else on the phone can read it.
   *Already extracted the archive on a computer?* Use **Import model folder** instead.
3. **Import local reply model** is optional: it accepts a `.litertlm` file (for example a
   Gemma 3 1B IT LiteRT-LM model) for open-ended chit-chat. Without it, commands still
   work and open-ended questions get a short explanation.

For spoken replies to stay offline, install an on-device TTS voice in
**Settings → System → Languages & input → Text-to-speech** and pick an offline voice.

## 3. Use it

| You say | What happens |
| --- | --- |
| "turn on the flashlight" / "switch the torch off" | Asks "Do you want to turn the flashlight on?" → tap **Confirm** or say **yes** |
| "set an alarm for seven thirty am" | Confirms, then hands the time to your clock app |
| "set a timer for ten minutes" | Confirms, then starts the timer |
| "how much battery is left" | Answers straight away (read-only, no confirmation) |
| "delete my notes file" | Explains that file deletion is unavailable — nothing is deleted |
| anything else | The intent brain tries to route it; if it cannot, you get the local reply model or an explanation |

Tap the orb to start listening, tap again to stop. While a confirmation is on screen you
can tap the orb and answer **yes** / **cancel** with your voice.

**Teach it your own wording.** In the *Teach JARVIS* panel, type a phrase (e.g. "make it
bright"), tap the action it should trigger, and from then on that phrase — and close
rephrasings of it — route to that action. Taught phrases live in the encrypted vault and
are checked *before* the neural network. Every confirmed action is appended to an
auditable action log, capped at 50 entries.

## 4. How the intent brain works

```
"make it bright"
      │
      ├─ rules (nlp/IntentRouter)         ── exact, unambiguous phrasing
      ├─ taught phrases (vault + cosine)  ── your own wording, learned on the phone
      └─ ai-matrix neural network         ── 4096 hashed features → 32 ReLU → 7 intents
                    │
                    ▼
             confirmation gate  ──▶  executor  ──▶  speech + vault log
```

`ai-matrix/` is a small NumPy project (35 pytest tests) that trains the network and
exports it to `app/src/main/assets/ai_matrix_model.json` (~655 KiB). The Kotlin port
(`aimatrix/`) runs the identical featurization and forward pass on the phone; the
Python-generated `tests/golden_features.json` snapshot pins both implementations to the
same buckets, so the two halves cannot silently drift apart. Held-out accuracy on
phrasing the model never saw during training: **0.93**.

## 5. Safety model

* Every state-changing intent (`ToggleFlashlight`, `SetAlarm`, `SetTimer`, `DeleteFile`)
  requires an explicit confirmation — tap or spoken "yes". The brain can only ever
  *suggest* a label; it cannot execute anything.
* `DeleteFile` is parsed and confirmed but has no executor: the assistant says deletion is
  unavailable instead of touching your files.
* Parameters (alarm times, timer durations) come from deterministic parsers, never from
  the classifier.
* No `INTERNET` permission; no analytics; no cloud fallback.
* The vault key is generated inside the Android Keystore (StrongBox when available) and is
  non-exportable. Losing the key means losing the vault contents — that is the point.

## 6. Build it yourself

Requirements: JDK 21 for the tooling (bytecode stays Java 17), Android SDK **API 36**, and
Gradle 8.14.6.

```sh
# the intent brain (Python 3.10+, numpy)
cd ai-matrix
python -m pip install -r requirements.txt
python -m pytest tests -q          # 35 tests
python -m ai_matrix.cli export     # rewrite app/src/main/assets/ai_matrix_model.json

# the Android app
cd ..
gradle :app:testDebugUnitTest :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/build.yml`) runs both jobs on every push and pull request: the
Python suite, then the Kotlin unit tests and the APK build. Builds on `main`, tags and
manual runs additionally publish the APK to the `jarvis-latest` release, and the debug
keystore is cached so each new APK installs over the previous one.

### Developer and verification guide

The [Build, Test, and Evaluation Guide](docs/BUILD_AND_VALIDATION_GUIDE.md) covers the
implementation map, the arithmetic behind the audio framing and routing thresholds, the
build/model setup, the on-device acceptance checklist, and how to measure WER, intent
accuracy, task success, safety and latency. A Word copy of the same document is generated
from it with `python docs/tools/build_guide_docx.py`:
[`docs/JARVIS_Build_Test_Evaluation_Guide.docx`](docs/JARVIS_Build_Test_Evaluation_Guide.docx).

Before the APK is uploaded, CI opens it and proves it is the offline assistant: package
`com.example.voiceassistant`, `minSdk 26 → targetSdk 36`, `arm64-v8a` native code, the
ai-matrix asset inside the archive, a valid signature, and **no `INTERNET` permission**. The
findings are attached to the run as an `APK verification` annotation, and any violation
fails the build instead of shipping. The same run also uploads a `junit-reports` artifact
and, if the suite fails, one annotation per failing test class.

## 7. Project layout

```
ai-matrix/            NumPy intent brain: features, model, training CLI, pytest suite
app/src/main/
├── assets/ai_matrix_model.json      trained weights bundled in the APK
├── java/com/example/voiceassistant/
│   ├── aimatrix/     Kotlin featurizer, MLP forward pass, bootstrapped brain
│   ├── execution/    executors (flashlight, alarm, timer, status), TTS, status provider
│   ├── generation/   optional LiteRT-LM conversational replies
│   ├── nlp/          normalizer, intent router, duration parser, two-stage processor
│   ├── orchestrator/ Hilt module + AssistantViewModel (the voice loop)
│   ├── statemachine/ AssistantUiStateMachine
│   ├── stt/          Vosk engine, audio capture, model import (folder or .zip)
│   ├── ui/           Compose screen (orb, confirmation card, teach panel)
│   └── vault/        SecureVault (AES-GCM) + JarvisMemory
└── res/              adaptive icon, dark theme
```

## 8. Known limits

* **Not** always-on: there is no wake word; the orb is push-to-talk.
* Offline speech recognition is good, not perfect — that is why device actions are
  confirmed, and why you can teach the assistant your own phrasing.
* Alarms and timers are handed to your clock app; the app itself does not keep a
  scheduler.
* The optional LiteRT-LM reply model is slow on 1B-class models and is never allowed to
  trigger actions.
