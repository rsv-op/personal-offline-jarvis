JARVIS — Local-First Android Assistant

JARVIS is an Android project intended to provide a privacy-focused, local-first assistant with optional, user-authorized device automation.

Current stage: Initial Android scaffold. The complete assistant is not yet implemented, and a successful APK build has not yet been verified.

Project goals

- Keep core processing and personal memory on-device wherever practical.
- Support offline voice interaction when the speech pipeline is implemented.
- Use Android's documented permission and accessibility mechanisms for authorized device interaction.
- Separate memory, voice, orchestration, and device-execution modules.
- Provide reproducible builds, automated tests, and verifiable APK artifacts.

Repository layout

personal-offline-jarvis/
├── .github/
│   └── workflows/
│       └── android-build.yml
├── docs/
│   └── architecture/
│       └── ADR-0001-local-first-android-core.md
├── jarvis-android/
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── AndroidManifest.xml
│   │   │   │   └── java/ai/arena/jarvis/MainActivity.java
│   │   │   └── test/
│   │   │       └── java/ai/arena/jarvis/BuildSanityTest.java
│   │   ├── build.gradle
│   │   └── proguard-rules.pro
│   ├── build.gradle
│   ├── gradle.properties
│   └── settings.gradle
├── .gitignore
├── FORGE_STATE.json
└── README.md

This tree describes the intended project layout. A file listed here may still need to be created and committed; the listing does not prove that every path exists in the repository.

Current implementation status

Prepared scaffold

- Java-based Android launcher activity.
- Basic status screen with a local button interaction.
- Gradle project and application module configuration.
- Initial JVM unit test.
- GitHub Actions workflow intended to test and assemble a debug APK.
- Initial architecture decision record.

Planned work — not yet implemented

- Encrypted local memory and hardware-backed key management.
- On-device semantic memory retrieval.
- Offline speech recognition and wake-word detection.
- User-enabled AccessibilityService for authorized interaction.
- Optional privileged integration for supported devices and explicitly authorized environments.
- Command orchestration connecting input, memory, decisions, and actions.
- Release signing, APK signature verification, and device installation tests.

Do not interpret planned capabilities as features available in the current application.

Build configuration

The current configuration is intended to use:

Component| Version
Java| 17
Gradle| 8.11.1
Android Gradle Plugin| 8.9.2
Compile SDK| 35
Minimum Android SDK| 26
JUnit| 4.13.2

These are configured versions, not evidence of a successful build. Dependency resolution and compilation must be confirmed by running the build.

Build locally

Install a compatible JDK 17 and Android SDK, then open a terminal in the repository root.

cd jarvis-android
gradle testDebugUnitTest --no-daemon
gradle assembleDebug --no-daemon

The expected debug APK path is:

jarvis-android/app/build/outputs/apk/debug/app-debug.apk

The commands above assume Gradle 8.11.1 is installed or otherwise available as "gradle". A Gradle wrapper has not been established by this scaffold.

GitHub Actions build

The workflow file is:

.github/workflows/android-build.yml

After committing the workflow and Android source files:

1. Open the repository's Actions tab.
2. Select JARVIS Android Build.
3. Choose Run workflow if automatic execution has not started.
4. Open the run and inspect the test and assembly logs.
5. Download the "JARVIS-debug-apk" artifact only if the workflow succeeds.

The workflow is intended to run JVM tests, assemble a debug APK, verify that the APK exists, and upload it as an artifact.

Install on a test device

After a successful build, enable Android debugging on a device you own or are authorized to test. With Android Debug Bridge (ADB) installed:

adb install -r jarvis-android/app/build/outputs/apk/debug/app-debug.apk

This installs a debug build. It is not a production release or a production-signing procedure.

Security and privacy

- Do not commit signing keys, credentials, private keys, or personal data.
- Request sensitive permissions only when the corresponding feature exists and needs them.
- Explain accessibility, microphone, VPN, or privileged access before asking the user to enable it.
- Keep optional network integrations separate from core local functions.
- Use explicit authorization and confirmation for sensitive device actions.
- Remember that ".gitignore" does not remove secrets already committed to Git history.

Verification checklist

- [ ] Required files committed to the repository.
- [ ] Gradle dependency resolution succeeds.
- [ ] JVM unit tests pass.
- [ ] Debug APK assembly succeeds.
- [ ] APK artifact uploaded by GitHub Actions.
- [ ] APK signature inspected.
- [ ] APK installed and launched on a supported device.
- [ ] Implemented functionality tested on-device.
- [ ] Offline claims verified with network access disabled.

Verification status: Until a real workflow run and device test confirm these steps, the build and installation remain unverified.

Contributing

Keep changes focused, use pinned dependency versions, add tests for implemented behavior, and update the architecture record when a significant design decision changes.

Do not mark a feature complete solely because its source file exists. Completion requires implementation and appropriate verification evidence.

License

Add the project's applicable license terms before distributing the software.
