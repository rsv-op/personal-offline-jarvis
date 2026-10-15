# Project structure

Three cooperating pieces: a single-module Android app, the NumPy intent brain that
trains its model, and the CI that produces an installable APK.

```text
.
├── .github/workflows/build.yml          pytest job + APK job + release publishing
├── README.md                            what it is, how to install it, how it works
├── project_structure.md                 this file
├── .gitignore
├── build.gradle.kts                     AGP 8.13.0, Kotlin 2.4.21, Hilt, Compose
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
    ├── build.gradle.kts                 compileSdk/targetSdk 36, phone ABIs, pytest deps
    └── src
        ├── main
        │   ├── AndroidManifest.xml      no INTERNET permission; <queries> for alarms/timers
        │   ├── assets/ai_matrix_model.json     trained weights (~655 KiB)
        │   ├── java/com/example/voiceassistant
        │   │   ├── MainActivity.kt             permission + model picker entry points
        │   │   ├── VoiceAssistantApplication.kt
        │   │   ├── aimatrix/
        │   │   │   ├── AiMatrixFeaturizer.kt   Kotlin port of features.py
        │   │   │   ├── AiMatrixClassifier.kt   MLP forward pass + JSON reader
        │   │   │   ├── AiMatrixModelStore.kt   loads the asset (IntentModelSource)
        │   │   │   └── OnDeviceBrain.kt        taught phrases, then the network
        │   │   ├── execution/
        │   │   │   ├── AssistantStatusProvider.kt  "what can you do" from real state
        │   │   │   ├── ExecutionCoordinator.kt
        │   │   │   ├── IntentExecutors.kt      flashlight, alarm, timer, status
        │   │   │   └── TtsController.kt        Android TextToSpeech + progress events
        │   │   ├── generation/
        │   │   │   ├── LiteRtLocalTextGenerator.kt   optional open-ended replies
        │   │   │   ├── LocalGenerationModelStore.kt  .litertlm import
        │   │   │   └── LocalTextGenerator.kt
        │   │   ├── nlp/
        │   │   │   ├── AppIntent.kt            intents + changesDeviceState
        │   │   │   ├── DurationParser.kt       "half an hour" → 1800
        │   │   │   ├── IntentRouter.kt         deterministic rules
        │   │   │   ├── NlpProcessor.kt         rules → brain, confirmation gating
        │   │   │   └── TextNormalizer.kt       shared filler list
        │   │   ├── orchestrator/
        │   │   │   ├── AssistantDiModule.kt    Hilt graph (vault, brain, dispatchers)
        │   │   │   └── AssistantViewModel.kt   the voice loop
        │   │   ├── statemachine/AssistantUiStateMachine.kt
        │   │   ├── stt/
        │   │   │   ├── AudioCaptureSource.kt   16 kHz mono frames
        │   │   │   ├── OfflineSttIntegrator.kt engine ↔ frames driver
        │   │   │   ├── SttEngine.kt, SttEvent.kt
        │   │   │   ├── VoskModelStore.kt       folder or .zip import (zip-slip safe)
        │   │   │   └── VoskSttEngine.kt
        │   │   ├── ui/AssistantScreen.kt       orb, confirmation card, teach panel
        │   │   └── vault/
        │   │       ├── JarvisMemory.kt         taught phrases, preferences, action log
        │   │       ├── SecureVault.kt          encrypted document store
        │   │       └── VaultCipher.kt          StrongBox/TEE AES-GCM + test cipher
        │   └── res/                            adaptive icon, dark theme, strings
        └── test/java/com/example/voiceassistant
            ├── TestFiles.kt                    repository-relative test fixtures
            ├── aimatrix/AiMatrixFeaturizerTest.kt    Python↔Kotlin parity
            ├── aimatrix/AiMatrixClassifierTest.kt    the shipped model's behaviour
            ├── aimatrix/OnDeviceBrainTest.kt         taught phrases vs the network
            ├── nlp/DurationParserTest.kt
            ├── nlp/IntentRouterTest.kt
            ├── nlp/NlpProcessorTest.kt
            ├── orchestrator/AssistantViewModelTest.kt  voice loop, confirmation gate
            ├── statemachine/AssistantUiStateMachineTest.kt
            ├── stt/OfflineSttIntegratorTest.kt
            ├── ui/AssistantScreenTest.kt
            └── vault/SecureVaultTest.kt, vault/JarvisMemoryTest.kt
```

## Data flow

1. `AudioCaptureSource` emits 16 kHz mono `ShortArray` frames.
2. `OfflineSttIntegrator` feeds them to `VoskSttEngine` and emits partial/final text.
3. `AssistantViewModel` runs `NlpProcessor`: `IntentRouter` rules first, then
   `OnDeviceBrain` (taught phrases from the vault, then the bundled ai-matrix network).
4. Anything that changes device state waits in `NeedsConfirmation` for a tap or a spoken
   "yes"; `DeleteFile` has no executor on purpose.
5. Executors perform the action, `TtsController` speaks the result, and `JarvisMemory`
   appends the outcome to the encrypted action log.

## Generated files

* `app/src/main/assets/ai_matrix_model.json` — regenerated by
  `cd ai-matrix && python -m ai_matrix.cli export`; committed because the APK bundles it.
* `ai-matrix/tests/golden_features.json` — regenerated by `python -m ai_matrix.golden`;
  only regenerate when a featurization change is intentional, because the Kotlin port must
  match it exactly.
