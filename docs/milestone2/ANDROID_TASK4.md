# Android Task 4 — Native notes application

## Outcome and provenance

Milestone 2 Task 4 implementation is complete as an **automated/build checkpoint**, not as an installed or visually accepted Android release.

- Incoming implementation: `forge/android-notes-shell` at `6171312f54386e0f09203ab84199861591d95071`, verified against the neutral bare Git repository.
- Finalization owner: ChatGPT/Sol, separate worktree `/home/reason/Temporary/ReasonNotes-Task4-Finalize`, branch `sol/android-task4-finalize`.
- The finalization changes documentation only. The APK below was compiled from the incoming implementation SHA.
- Reference design: `docs/superpowers/specs/2026-10-15-android-task4-notes-shell-design.md`.
- Task plan: `docs/superpowers/plans/2026-10-15-android-task4-implementation.md`.

## Implemented components

The existing Java `ProbeRepository`, immutable `RevisionEnvelope` history, `RevisionGraph`, `DeliveryLedger`, `ReceiptStore`, `ExchangeDirectory` and `KdeConnectSender` remain the underlying data and transport system.

- `NoteDocument` mirrors the Fedora Markdown title/body convention: separate editable presentation fields, one serialized Markdown document, and no read-time rewrite of legacy notes.
- `NoteService` provides note CRUD, unchanged-save coalescing, Unicode-aware search, tombstone deletion, full conflict inspection, and exact-head guarded revisions.
- `DraftStore` and `EditorAutosaveController` retain recovery drafts, protect newer generations against older save completions, and reject stale bases.
- `PinStore` keeps pinned note UUIDs locally, independent of Markdown history and synchronization.
- `AppPreferences` manages Android-local behavior/device choices without replacing the existing persisted SAF grant.
- `MainActivity`: rounded two-column cards divided into Pinned and Others, full-width New Note action, Settings gear, bottom search, and operational long-press actions.
- `EditorActivity`: title/body editor with autosave, recovery and status.
- `SettingsActivity`: available settings and existing exchange-directory setup.
- `ConflictActivity`: full vertically stacked conflict heads, including edit-versus-delete and exact revision Keep/Skip behavior.
- `SyncPlanner`, `SyncCoordinator`, `SyncActivity`: all/selected outgoing revisions; scan-first incoming imports; unconditional receipt handling; explicit transfer-requested versus confirmed evidence.

Application data compatibility is preserved at the source level: `files/probe-repository`, `files/delivery-state`, preferences `reasonnotes-exchange` / `tree-uri`, and the initial Linux KDE Connect target ID `254997f28f11499d80928fe8a109749e`. The package remains `org.reasonnotes.probe`. All non-launcher Android activities remain non-exported.

## Finalization verification, 2026-10-15

The Android test/build environment was:

- Java 17: `/home/reason/Temporary/ReasonNotesToolchain/install/jdk-17.0.20.1+1`.
- Android SDK: `/home/reason/Temporary/ReasonNotesToolchain/install/android-sdk`, build tools 35.0.0.
- Gradle wrapper 8.9 and `GRADLE_USER_HOME=/home/reason/Temporary/ReasonNotesToolchain/gradle-home`.
- All commands ran against the Sol finalization worktree, **not** the Forge worktree.

Commands (with `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `GRADLE_USER_HOME` set to the paths above):

```sh
cd /home/reason/Temporary/ReasonNotes-Task4-Finalize/android-probe
./gradlew --offline --no-daemon testDebugUnitTest
./gradlew --offline --no-daemon assembleDebug
```

**Result:** 23 JUnit XML test-suite files; **93 tests passed, 0 failures, 0 errors, 0 skipped**. The final test run included Forge's delivery-ledger path correction at `6171312`. Gradle reported `BUILD SUCCESSFUL` for both tests and APK assembly.

The build emitted the standard Android SDK XML version-warning and an Android deprecated-API compiler note for `EditorActivity`; neither failed the build. These were **host JVM tests and build checks**, not POCO instrumentation or on-device functional testing.

## Candidate APK and signing evidence

- Preserved artifact: `/home/reason/Temporary/ReasonNotes-Task4-Finalize-artifacts/ReasonNotes-Android-Task4-6171312-07fe3bee.apk`.
- Gradle output: `android-probe/app/build/outputs/apk/debug/app-debug.apk` in the finalization worktree.
- Artifact size: **2,344,028 bytes**.
- SHA-256 (both paths match): `07fe3beea73d5af4c4a497a6998cca5d02bd5d4774c7dcab5807a1a7a0eec752`.
- Package: `org.reasonnotes.probe`; version code **1**, version name **0.1**; minimum SDK **26**, target SDK **35**.
- Launcher: `org.reasonnotes.probe.MainActivity`.
- `apksigner verify --verbose --print-certs`: signature verifies using APK Signature Scheme **v2**.
- Debug certificate SHA-256: `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147`.

The preserved, physically-tested Milestone 1 APK (`ReasonNotes-Probe-multinote-7d9c597-31002579.apk`, SHA-256 `310025791599f0c226cfaef5f59f6d143d2cdf23578b3436927165d60936d03e`) was inspected read-only and carries **the same signer certificate SHA-256**. This establishes compatibility with that **reference APK**, not proof of the certificate currently installed on the POCO.

Before any future installation, Task 5 must compare the actual installed package/signing identity with the candidate; inspect version/data-migration implications, especially the unchanged versionCode; preserve the existing internal repository, delivery ledger, receipt state, SAF permission and preference keys; and stop if compatibility cannot be demonstrated. Do **not** uninstall/clear application data as a workaround. HyperOS previously restricted ADB installation; owner approval is required for installation.

## Unverified and explicitly deferred

- No candidate APK installed, no POCO files/settings touched, no physical Android runtime or visual acceptance.
- The new Milestone 2 receipt path has **not** yet been physically confirmed between this Android application and Fedora.
- No two-device convergence, save/restart, selective-sync or conflict acceptance on the physical phone.
- Android theme colors are temporary; **White, Dark and LiquidGlass are a separate milestone**.
- Cross-device pin synchronization remains deferred; pins currently remain local per device.
- Device-side app installation, migration and Milestone 2 Task 5 require new, separate owner approval.

Next gate: owner reviews this completed Task 4 build and separately authorizes any physical installation/acceptance. Do not start themes or Task 5 from this checkpoint automatically.
