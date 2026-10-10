ADR-0001: Local-First Android Core

- Status: Accepted as the intended architecture; implementation is incomplete
- Date: 2026-10-11
- Project: JARVIS Android
- Decision owners: Project maintainers

1. Context

JARVIS is intended to be an Android assistant whose primary memory and core decision-making can operate locally on the device. Network-dependent functionality must remain optional and must not be required for basic local operation.

The initial application scaffold consists of a Java-based Android launcher screen and a basic local status interaction. The current scaffold does not yet implement the complete assistant, encrypted memory, offline speech recognition, or device automation.

2. Decision

The project will follow these architectural principles:

1. Local-first operation: Core functions should work without requiring a cloud service.
2. Data minimization: Store only information required by implemented features.
3. Explicit permissions: Request sensitive Android permissions only when a corresponding feature is implemented and the user enables it.
4. Protected secrets: Do not commit credentials, private keys, signing secrets, or personal data to the source repository.
5. Modular execution: Separate memory, voice input, intent processing, device interaction, and user interface into independently testable components.
6. Authorized interaction: Use documented Android APIs and user-authorized capabilities. Privileged operations must be opt-in and restricted to supported devices and permissions.
7. Verifiable delivery: Treat tests, successful builds, APK signature checks, and installation tests as separate verification steps.

3. Intended architecture

The planned request flow is:

1. Receive a user command through the interface or a future offline voice component.
2. Interpret the command using local processing where supported.
3. Retrieve relevant local context when memory functionality is implemented.
4. Select an authorized action supported by the installed application and device.
5. Request confirmation when an action is sensitive or consequential.
6. Execute the action through the appropriate Android API.
7. Report the actual result and record permitted local history.

This describes the intended design, not a currently implemented end-to-end pipeline.

4. Implementation status

Implemented in the initial scaffold

- A Java Android launcher activity.
- A basic status screen.
- A local button interaction that updates displayed status text.
- Initial Gradle configuration and a basic JVM unit-test source file.

These files have been prepared for repository creation. Their presence in a proposed source tree does not prove that they have been committed, compiled, or executed successfully.

Planned, not yet implemented

- Encrypted local database and hardware-backed key management.
- Semantic or vector memory retrieval.
- Offline speech recognition and wake-word detection.
- Accessibility-based interaction with an appropriate user-enabled service.
- Optional privileged execution using supported, explicitly authorized mechanisms.
- A command orchestration engine connecting input, memory, decisions, and actions.
- Production signing, release verification, and device installation tests.

5. Security and privacy

- Keep core memory on-device by default.
- Encrypt sensitive persisted data when the memory subsystem is implemented.
- Avoid logging secrets, raw credentials, or unnecessary personal information.
- Explain the purpose of sensitive permissions before requesting them.
- Do not bypass Android permission controls or silently perform unauthorized actions.
- Treat root, Shizuku, accessibility, and VPN capabilities as separate features with distinct security implications.
- Keep signing credentials outside version control and use protected build secrets for release automation.

6. Consequences

Benefits

- Reduces dependence on external services for core functionality.
- Makes privacy boundaries easier to inspect.
- Supports incremental testing and independent module development.
- Allows honest capability reporting when a service is unavailable.

Trade-offs

- On-device speech and inference can require significant CPU, memory, storage, and battery resources.
- Background execution is constrained by Android version, permissions, and device manufacturer behavior.
- Accessibility and privileged capabilities require explicit setup and cannot be assumed to be available.
- Offline functionality may be limited until suitable on-device models and data are packaged.

7. Verification criteria

Before the project can be described as a functioning end-to-end assistant, the maintainers must verify:

- Gradle configuration and compilation succeed.
- Automated tests execute and pass.
- The APK exists and its signature is verified.
- The APK installs and launches on a supported Android device.
- Implemented features work with networking disabled where offline operation is claimed.
- Permission and privacy behavior matches the documented design.

Current verification status: These criteria have not yet been demonstrated by a completed build and device test.

8. Revisit conditions

Review this decision if the project adopts a mandatory remote inference service, changes its core memory storage model, adds a new privileged execution mechanism, or changes its minimum supported Android version.
