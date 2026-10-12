# ReasonNotes — Project Status

## Current milestone

Milestone 1: KDE Connect synchronization feasibility. Implementation Tasks 1–9 are complete (Forge). Physical POCO acceptance is in progress: **P0 passed, P1 passed with installation workaround, P2 blocked** on an unrooted UI anomaly plus a confirmed Android routing bug whose fix has not been applied.

## Current branch and provenance

- Incoming Forge checkpoint: `d60c37dda9962ec8c5d9756a3fda7d219d45eb08` (branch `forge/kdeconnect-feasibility`, neutral remote)
- Qwen working branch: `qwen/kdeconnect-acceptance`, created at the exact incoming SHA; worktree verified clean at start of shift and before this documentation commit
- Last completed implementation commit (unchanged): `926099ec1f28fc7b08eabd44b1b5c2a6e58e8491`

## Current owner

Forge. Qwen's shift ended at handoff; **Qwen made no source changes** (verified by clean `git status`/`git diff` before this documentation commit).

## Physical acceptance state (POCO X6 5G, model 23122PCD1G, Android 16)

- **P0 — PASS**: ADB device serial `59dffe68` (usb:1-3, product `garnetp_global`) authorized. USB debugging and "Install via USB" were already enabled on the phone.
- **P1 — PASS with workaround**: frozen app APK SHA-256 `ef19db85b723b6e495759cb5cb48313157801f20fc3f4260b4f1703d612f1c90` verified on host; HyperOS rejected `adb install -r` twice with `INSTALL_FAILED_USER_RESTRICTED` ("Rejected ReasonNotes KDE Connect Probe installation"), so the APK was pushed to `/sdcard/Download/ReasonNotes-Probe.apk` (on-device SHA-256 exact match) and installed manually via file manager. ADB verified package `org.reasonnotes.probe`, version 0.1 / versionCode 1, targetSdk 35, DEBUGGABLE, running at check time. KDE Connect Android (`org.kde.kdeconnect_tp`) is present on the phone.
- **P2 — BLOCKED**: user sees only "Sync now" and "Retry unconfirmed"; expected controls "Choose ReasonNotes Exchange" and "Create deterministic probe note" not visible; status area empty. Source inspection: `MainActivity.onCreate` adds all four buttons unconditionally inside a ScrollView, so no layout defect is proven yet (an earlier uiautomator capture grabbed the SystemUI/AOD window, not the app). Root cause unknown.
- **No synchronization of any kind has been demonstrated** during this shift.

## Confirmed routing bug (fix owned by Forge)

- `MainActivity.KDE_DEVICE_ID` currently holds `52548cf9710c43cab2fa73ed4671aea9`, the POCO's **own** KDE Connect ID, but the Android→Linux send must target the Linux peer.
- Correct destination (M A C H I N E): `254997f28f11499d80928fe8a109749e`, independently verified on the host via `kdeconnect-cli --my-id` and the KDE Connect certificate common name.
- Mechanics: `KdeConnectSender` passes that value as the `deviceId` intent extra to KDC's exported `ShareActivity`.
- The `PROBE_NOTE` UUID (same hex string, dashed) is a note namespace, must remain unchanged, and is unrelated to transfer routing.

## Environment facts verified this shift (reuse directly)

- Executor shell has no useful PATH or D-Bus env: use absolute paths; for `kdeconnect-cli` set `DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus`.
- `/usr/bin/kdeconnect-cli --list-devices` on the host shows "moto g13" (`4b463f9b8cb045df8758ed50a756f747`, paired) and POCO X6 5G (`52548cf9…`, LAN-reachable).
- Toolchain: JDK Temurin `17.0.20.1+1` and adb v37.0.1 under `/home/reason/Temporary/ReasonNotesToolchain/install/{jdk-17.0.20.1+1,android-sdk}`; warm Gradle cache at `GRADLE_USER_HOME=/home/reason/Temporary/ReasonNotesToolchain/gradle-home`; no repo `local.properties`, so exports for `JAVA_HOME`, `ANDROID_HOME` (or SDK root), and `GRADLE_USER_HOME` are required before `./gradlew`.

## Verification state / evidence boundary

- Not verified: any ReasonNotes synchronization; SAF tree grant on device; Android→Linux application transfer; Linux→Android import; conflict resolution on hardware; the UI anomaly's root cause.
- No project test suites were executed this shift (no Gradle, no Python). Passing host tests, source inspection, and a successful installation prove none of the above.

## Next action

Forge: foreground the app to capture the real view hierarchy; fix `KDE_DEVICE_ID` to `254997f28f11499d80928fe8a109749e` with focused regression coverage; rebuild a replacement APK, report its SHA-256, and install via the file-manager fallback (HyperOS rejects ADB installs); then resume `docs/milestone1/PHONE_TEST_PROCEDURE.md` from P2.

## Important constraints

- Forge owns /home/reason/Codex-Projects/ReasonNotes; Qwen owns /home/reason/Temporary/QwenProjects/ReasonNotes; exchange committed work through handoff only.
- Preserve meaningful work with commits/checkpoints before switching agents.
- Never claim Android integration or bidirectional convergence before physical evidence exists.
