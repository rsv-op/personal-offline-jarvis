# Project structure

Three cooperating pieces: a single-module Android app, the NumPy intent brain that
trains its model, and the CI that produces an installable APK.

```text
.
├── .github/workflows/build.yml          pytest job + APK job + release publishing
│                                        (also verifies the built APK and republishes
│                                         failures, and the APK checks, as annotations)
├── README.md                            what it is, how to install it, how it works
├── project_structure.md                 this file
├── docs/                                build, test and evaluation documentation
│   ├── BUILD_AND_VALIDATION_GUIDE.md    the authoritative guide (edit this one)
│   ├── JARVIS_Build_Test_Evaluation_Guide.docx   generated Word copy
│   └── tools/build_guide_docx.py        regenerates the Word copy from the markdown
├── .gitignore
├── build.gradle.kts                     AGP 8.13.0, Kotlin 2.4.21, KSP, Hilt, Compose
├── settings.gradle.kts
├── gradle.properties
│
├── ai-matrix/                           the offline intent brain (Python + NumPy)
│   ├── README.md                        design, measured accuracy, commands
│   ├── requirements.txt                 numpy, pytest
│   ├── ai_matrix/
│   │   ├── __init__.py
│   │   ├── features.py                  deterministic featurizer (Python↔Kotlin contract)
│   │   ├── model.py                     2-layer perceptron, hand-written Adam
│   │   ├── dataset.py                   7 intents, 169 teaching utterances
│   │   ├── export.py                    training pipeline + JSON export
│   │   ├── golden.py                    regenerates tests/golden_features.json
│   │   └── cli.py                       train / evaluate / export / predict
│   └── tests/
│       ├── golden_features.json         frozen bucket counts consumed by the Kotlin test
│       ├── test_features.py
│       ├── test_model.py
│       └── test_export.py
│
└── app/
    ├── build.gradle.kts                 compileSdk/targetSdk 36, phone ABIs, CMake +
    │                                    Room (under KSP), pytest deps
    ├── proguard-rules.pro               keeps the JNI bridge and the Room implementation
    └── src
        ├── main
        │   ├── AndroidManifest.xml      no INTERNET permission; foreground service,
        │   │                            contacts + notification permissions
        │   ├── assets/ai_matrix_model.json     trained weights (~655 KiB)
        │   ├── cpp/
        │   │   ├── CMakeLists.txt               llama.cpp build + the JNI bridge
        │   │   └── jarvis_llama.cpp             JNI: load/unload, chat template,
        │   │                                    raw-byte token callback, cancellation
        │   ├── java/com/example/voiceassistant
        │   │   ├── MainActivity.kt             permission + model picker entry points
        │   │   ├── VoiceAssistantApplication.kt
        │   │   ├── aimatrix/
        │   │   │   ├── AiMatrixFeaturizer.kt   Kotlin port of features.py
        │   │   │   ├── AiMatrixClassifier.kt   MLP forward pass + JSON reader
        │   │   │   ├── AiMatrixModelStore.kt   loads the asset (IntentModelSource)
        │   │   │   └── OnDeviceBrain.kt        taught phrases, then the network
        │   │   ├── chat/
        │   │   │   ├── ChatMessageEntity.kt    one transcript row
        │   │   │   ├── ChatMessageDao.kt       queries + the transactional beginTurn
        │   │   │   ├── ChatDatabase.kt         Room database, WAL journalling
        │   │   │   └── ChatHistoryRepository.kt turns, completion, recovery
        │   │   ├── execution/
        │   │   │   ├── AssistantStatusProvider.kt  "what can you do" from real state
        │   │   │   ├── ContactsExecutor.kt     read-only contact lookup
        │   │   │   ├── ExecutionCoordinator.kt
        │   │   │   ├── IntentExecutors.kt      flashlight, alarm, timer, status
        │   │   │   └── TtsController.kt        Android TextToSpeech + progress events
        │   │   ├── generation/
        │   │   │   ├── LocalTextGenerator.kt          the two-method seam
        │   │   │   ├── RuleBasedLocalTextGenerator.kt llama routing + honest fallback
        │   │   │   └── llama/
        │   │   │       ├── LlamaNative.kt             JNI declarations + token sink
        │   │   │       ├── LlamaLocalTextGenerator.kt  the llama.cpp backend
        │   │   │       ├── LlamaModelStore.kt          .gguf import + copy
        │   │   │       ├── LlamaModelService.kt        foreground service, unload
        │   │   │       │                              under memory pressure
        │   │   │       ├── ModelTierPicker.kt          RAM-based model size tiers
        │   │   │       └── Utf8StreamDecoder.kt        raw bytes → UTF-8 text
        │   │   ├── memory/MemoryBudget.kt        pure memory-headroom helper
        │   │   ├── nlp/
        │   │   │   ├── AppIntent.kt            intents + changesDeviceState
        │   │   │   ├── DurationParser.kt       "half an hour" → 1800
        │   │   │   ├── IntentRouter.kt         deterministic rules
        │   │   │   ├── NlpProcessor.kt         rules → brain, confirmation gating
        │   │   │   └── TextNormalizer.kt       shared filler list
        │   │   ├── orchestrator/
        │   │   │   ├── AssistantDiModule.kt    Hilt graph (vault, brain, dispatchers,
        │   │   │   │                            Room, llama, contacts)
        │   │   │   └── AssistantViewModel.kt   the voice loop, chat state
        │   │   ├── statemachine/AssistantUiStateMachine.kt
        │   │   ├── stt/
        │   │   │   ├── AudioCaptureSource.kt   16 kHz mono frames
        │   │   │   ├── OfflineSttIntegrator.kt engine ↔ frames driver
        │   │   │   ├── SttEngine.kt, SttEvent.kt
        │   │   │   ├── SherpaOnnxModelStore.kt  folder or .zip import (zip-slip safe)
        │   │   │   ├── SherpaOnnxModelValidator.kt
        │   │   │   └── SherpaOnnxSttEngine.kt
        │   │   ├── ui/AssistantScreen.kt       orb, confirmation card, teach panel
        │   │   ├── ui/ChatPanel.kt            the chat transcript
        │   │   └── vault/
        │   │       ├── JarvisMemory.kt         taught phrases, preferences, action log
        │   │       ├── SecureVault.kt          encrypted document store
        │   │       └── VaultCipher.kt          StrongBox/TEE AES-GCM + test cipher
        │   └── res/                            adaptive icon, dark theme, strings
        └── test/java/com/example/voiceassistant
            ├── TestFiles.kt                    repository-relative test fixtures
            ├── aimatrix/                       Python↔Kotlin parity, taught phrases
            ├── chat/ChatMessageDaoTest.kt      the Room transcript
            ├── generation/
            │   ├── RuleBasedLocalTextGeneratorTest.kt
            │   └── llama/ModelTierPickerTest.kt, llama/Utf8StreamDecoderTest.kt
            ├── memory/MemoryBudgetTest.kt
            ├── nlp/                           routing, durations, contacts
            ├── orchestrator/AssistantViewModelTest.kt  voice loop, confirmation gate
            ├── statemachine/AssistantUiStateMachineTest.kt
            ├── stt/                           sherpa-onnx engine, validator, extractor
            ├── telematics/                    system-metrics parsers
            ├── ui/AssistantScreenTest.kt
            └── vault/SecureVaultTest.kt, vault/JarvisMemoryTest.kt
```

## Data flow

1. `AudioCaptureSource` emits 16 kHz mono `ShortArray` frames.
2. `OfflineSttIntegrator` feeds them to `SherpaOnnxSttEngine` and emits partial/final text.
3. `AssistantViewModel` runs `NlpProcessor`: `IntentRouter` rules first, then
   `OnDeviceBrain` (taught phrases from the vault, then the bundled ai-matrix network).
4. Anything that changes device state waits in `NeedsConfirmation` for a tap or a spoken
   "yes"; `DeleteFile` has no executor on purpose. A contact lookup changes nothing, so it
   is answered without a confirmation.
5. Executors perform the action, `TtsController` speaks the result, and `JarvisMemory`
   appends the outcome to the encrypted action log.
6. A conversational turn is written to the Room transcript: the user's message and a
   pending reply placeholder in one transaction, then the placeholder is filled in with
   the finished reply. A reply that is still pending on the next launch was interrupted by
   a process death and is marked as such.

## Generated files

* `app/src/main/assets/ai_matrix_model.json` — regenerated by
  `cd ai-matrix && python -m ai_matrix.cli export`; committed because the APK bundles it.
* `ai-matrix/tests/golden_features.json` — regenerated by `python -m ai_matrix.golden`;
  only regenerate when a featurization change is intentional, because the Kotlin port must
  match it exactly.
* `docs/JARVIS_Build_Test_Evaluation_Guide.docx` — regenerated by
  `python docs/tools/build_guide_docx.py`; the markdown next to it is the source of truth.
