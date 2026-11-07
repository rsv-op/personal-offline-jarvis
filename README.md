# JARVIS — a fully offline Android voice assistant

A single Android app (package `com.example.voiceassistant`, label **JARVIS**) that listens,
understands, confirms and acts — entirely on your phone. Speech recognition, the intent
brain, the local language model and the encrypted memory all run on the device. **The app
does not hold the `INTERNET` permission at all**, so it cannot send your voice anywhere
even if it tried.

| Piece | What it does | Runs on |
| --- | --- | --- |
| sherpa-onnx (`stt/`) | Streaming speech → text, 16 kHz mono, no network | Your phone |
| ai-matrix (`ai-matrix/`, `aimatrix/`) | Phrase → intent, a 131k-parameter neural net | Trained on a laptop, **runs** on your phone |
| Rules (`nlp/`) | Alarm times, timer durations, on/off polarity, contact lookups | Your phone |
| llama.cpp (`generation/llama/`, `cpp/`) | *Optional* open-ended answers from a `.gguf` model you import | Your phone |
| Room (`chat/`) | The conversation transcript, in WAL-journalled SQLite | Your phone |
| Vault (`vault/`) | Taught phrases + preferences, AES-GCM under a StrongBox/TEE key | Your phone |
| Android `TextToSpeech` | Replies out loud (install an offline voice pack) | Your phone |

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
4. Open **JARVIS** and grant the microphone, camera, notification and contacts
   permissions (microphone for speech, camera for the flashlight torch, contacts for
   looking up a phone number).

## 2. Give it a voice (one-time)

The speech model is intentionally **not** bundled — it is large and licensed separately.

The app uses **sherpa-onnx** Streaming Zipformer (English, INT8). The model archive is
published with the sherpa-onnx releases under the `asr-models` tag, for example:

```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2
```

The full list is at
<https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html>.

1. In JARVIS tap **Import model .zip** and pick the archive. The app unzips it itself
   (Android's Files app cannot), validates it, and copies it into private storage, so
   nothing else on the phone can read it.
   *Already extracted the archive on a computer?* Use **Import model folder** instead.
   After a good import the status line reports the model size. A missing or `0 MB` size
   means the import did not land, and the assistant refuses to listen with an incomplete
   model rather than failing later.

The importer checks that the archive really contains a streaming Zipformer
(`tokens.txt` plus `encoder-*.onnx`, `decoder-*.onnx` and `joiner-*.onnx`, and at least
1 MiB of data), so a truncated or unrelated download is refused with a reason instead of
failing later at the orb.

For spoken replies to stay offline, install an on-device TTS voice in
**Settings → System → Languages & input → Text-to-speech** and pick an offline voice.

## 3. Give it a brain (optional, 0.5–4 GB)

**Import local reply model** accepts a **`.gguf`** file. Without one, every command still
works and open-ended questions get a short, honest, built-in answer instead of a
pre-empt. The model is chosen for your phone's memory — the app shows a recommendation
("Tiny / Small / Medium") based on how much RAM is free.

There is **no downloader**: the app has no `INTERNET` permission, so a model can only
arrive as a file you pick yourself.

| Model size | Parameters | Free RAM needed (2.5× headroom) |
| --- | --- | --- |
| Tiny (≤1.2 GB) | ≈1B, Q4 | ≥3.0 GB |
| Small (≤2.4 GB) | ≈3B, Q4 | ≥6.0 GB |
| Medium (≤4.6 GB) | ≈7–8B, Q4 | ≥11.5 GB |

**Expect a slow first answer.** A multi-gigabyte model takes **5–15 seconds** to load
the first time; after that the foreground service keeps it resident and replies start
almost immediately. The app says so rather than pretending to be instant. The model is
unloaded again automatically when the device runs short of memory.

llama.cpp runs on the CPU with NEON/dotprod kernels. **Vulkan is off by default** —
on the Adreno generation this app targets, the CPU kernels are usually faster.

## 4. Use it

| You say | What happens |
| --- | --- |
| "turn on the flashlight" / "switch the torch off" | Asks "Do you want to turn the flashlight on?" → tap **Confirm** or say **yes** |
| "set an alarm for seven thirty am" | Confirms, then hands the time to your clock app |
| "set a timer for ten minutes" | Confirms, then starts the timer |
| "how much battery is left" | Answers straight away (read-only, no confirmation) |
| "what is Priya's number?" | Looks up the contact and reads the number (read-only, no confirmation) |
| "delete my notes file" | Explains that file deletion is unavailable — nothing is deleted |
| anything else | The intent brain tries to route it; if it cannot, you get the local model or an explanation |

Tap the orb to start listening, tap again to stop. While a confirmation is on screen you
can tap the orb and answer **yes** / **cancel** with your voice.

**The conversation is kept.** Tap **Open conversation** for a chat transcript. It is
stored in Room with WAL journalling, so a reply that is still being written never blocks
the reader. If the app is killed mid-answer, the next launch marks that reply as
interrupted instead of leaving a blank bubble.

**Teach it your own wording.** In the *Teach JARVIS* panel, type a phrase (e.g. "make it
bright"), tap the action it should trigger, and from then on that phrase — and close
rephrasings of it — route to that action. Taught phrases live in the encrypted vault and
are checked *before* the neural network. Every confirmed action is appended to an
auditable action log, capped at 50 entries.

## 5. How the intent brain works

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

## 6. Safety model

* Every state-changing intent (`ToggleFlashlight`, `SetAlarm`, `SetTimer`, `DeleteFile`)
  requires an explicit confirmation — tap or spoken "yes". The brain can only ever
  *suggest* a label; it cannot execute anything.
* `DeleteFile` is parsed and confirmed but has no executor: the assistant says deletion is
  unavailable instead of touching your files.
* The language model only ever produces *speech*. It never selects or executes an
  action — that stays with the deterministic router and the confirmation gate, so a
  hallucinated reply can never become an unconfirmed action.
* Parameters (alarm times, timer durations) come from deterministic parsers, never from
  the classifier.
* No `INTERNET` permission; no analytics; no cloud fallback.
* The vault key is generated inside the Android Keystore (StrongBox when available) and is
  non-exportable. Losing the key means losing the vault contents — that is the point.
* The chat transcript is in Room and is **not** encrypted. That is deliberate: the
  transcript is a convenience. The things that need protecting — taught phrases and
  stored preferences — stay in the encrypted vault.

## 7. Build it yourself

Requirements: JDK 21 for the tooling (bytecode stays Java 17), Android SDK **API 36**,
the **Android NDK** (the llama.cpp bridge is compiled from source), **CMake 3.22.1**, and
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

The build downloads the pinned llama.cpp release (`b12189`) into `app/build/llama-cpp/`
and compiles it together with `app/src/main/cpp/jarvis_llama.cpp`, the JNI bridge.

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
ai-matrix asset inside the archive, the llama.cpp bridge present for arm64 (and *absent*
for 32-bit), a valid signature, and **no `INTERNET` permission**. The findings are
attached to the run as an `APK verification` annotation, and any violation fails the
build instead of shipping. The same run also uploads a `junit-reports` artifact and, if
the suite fails, one annotation per failing test class.

## 8. Project layout

```
ai-matrix/            NumPy intent brain: features, model, training CLI, pytest suite
app/src/main/
├── assets/ai_matrix_model.json      trained weights bundled in the APK
├── cpp/               llama.cpp JNI bridge (jarvis_llama.cpp) + CMakeLists.txt
├── java/com/example/voiceassistant/
│   ├── aimatrix/     Kotlin featurizer, MLP forward pass, bootstrapped brain
│   ├── chat/         Room transcript: entity, DAO, database, repository
│   ├── execution/    executors (flashlight, alarm, timer, status, contacts), TTS
│   ├── generation/   llama.cpp conversational replies + the rule-based fallback
│   │   └── llama/    JNI bindings, UTF-8 stream decoder, model store, tier picker
│   ├── nlp/          normalizer, intent router, duration parser, two-stage processor
│   ├── orchestrator/ Hilt module + AssistantViewModel (the voice loop)
│   ├── statemachine/ AssistantUiStateMachine
│   ├── stt/          sherpa-onnx engine, audio capture, model import (folder or .zip)
│   ├── ui/           Compose screen (orb, confirmation card, teach panel, chat)
│   └── vault/        SecureVault (AES-GCM) + JarvisMemory
└── res/              adaptive icon, dark theme
```

## 9. Known limits

* **Not** always-on: there is no wake word; the orb is push-to-talk.
* Offline speech recognition is good, not perfect — that is why device actions are
  confirmed, and why you can teach the assistant your own phrasing.
* Alarms and timers are handed to your clock app; the app itself does not keep a
  scheduler.
* The optional llama.cpp model is slow to load (5–15 s cold start) and is never allowed
  to trigger actions.
* The chat transcript is not encrypted; only the vault is.
* No RAG, no always-on listening, and no calendar/SMS tools yet.
