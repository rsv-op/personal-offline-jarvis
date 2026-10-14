# Project structure

This repository is a single-module Android application. Kotlin upload wrappers (`.kt.txt`) are represented below with normal `.kt` source filenames. The duplicate STT engine-interface upload is consolidated into `SttEngine.kt`.

```text
.
├── .github/workflows/build.yml
├── app
│   ├── build.gradle.kts
│   └── src
│       ├── main
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/voiceassistant
│       │   │   ├── MainActivity.kt
│       │   │   ├── VoiceAssistantApplication.kt
│       │   │   ├── execution
│       │   │   │   ├── ExecutionCoordinator.kt
│       │   │   │   ├── IntentExecutors.kt
│       │   │   │   └── TtsController.kt
│       │   │   ├── nlp
│       │   │   │   ├── AppIntent.kt
│       │   │   │   ├── IntentRouter.kt
│       │   │   │   ├── NlpProcessor.kt
│       │   │   │   └── TextNormalizer.kt
│       │   │   ├── orchestrator
│       │   │   │   ├── AssistantDiModule.kt
│       │   │   │   └── AssistantViewModel.kt
│       │   │   ├── statemachine/AssistantUiStateMachine.kt
│       │   │   ├── stt
│       │   │   │   ├── OfflineSttIntegrator.kt
│       │   │   │   ├── SttEngine.kt
│       │   │   │   └── SttEvent.kt
│       │   │   └── ui/AssistantScreen.kt
│       │   ├── res
│       │   │   ├── values/{strings.xml,themes.xml}
│       │   │   └── xml/{backup_rules.xml,data_extraction_rules.xml}
│       └── test/java/com/example/voiceassistant
│           ├── nlp/NlpProcessorTest.kt
│           ├── orchestrator/AssistantViewModelTest.kt
│           ├── stt/OfflineSttIntegratorTest.kt
│           └── ui/AssistantScreenTest.kt
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

The original uploads `STT Engine Interface.kt.txt` and `SttEngine.kt.txt` describe the same interface, so they are represented once. `DeleteFile` is part of the intent model for parsing/confirmation only; no destructive file executor is wired into the app.
