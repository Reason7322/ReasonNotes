# Qwen -> Forge Handoff

## Checkpoint

- Sender branch: `qwen/kdeconnect-acceptance` (created at the exact incoming SHA)
- Incoming/base checkpoint: `d60c37dda9962ec8c5d9756a3fda7d219d45eb08` (= `forge/kdeconnect-feasibility` head = implementation `926099ec…` + docs-only changes)
- This handoff is a documentation commit on Qwen's branch; the pushed SHA is reported by Qwen after push.

## Delta since d60c37d (what this shift actually did)

- Physical acceptance advanced: **P0 and P1 passed, P2 blocked**. Details below with evidence.
- **No application source was changed.** Worktree verified clean at the incoming SHA before this documentation commit; an earlier Qwen edit attempt to `MainActivity` never landed (no modified or stray tracked files). There is no WIP to preserve beyond these docs.
- No project test suites were run this shift (no Gradle, no Python invocations completed). All new evidence is live ADB/host CLI observation from 2026-10-12 evening.

## Physical evidence (live ADB, POCO X6 5G)

### P0 — PASS
- `adb devices -l`: serial `59dffe68`, status `device` (authorized), usb:1-3, product `garnetp_global`.
- Device props: brand POCO, model `23122PCD1G`, Android 16 (`ro.build.version.release`).

### P1 — PASS with installation workaround
- Frozen app APK SHA-256 on host (verified twice): `ef19db85b723b6e495759cb5cb48313157801f20fc3f4260b4f1703d612f1c90`.
- `adb install -r` rejected **twice** by the phone with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`; HyperOS showed "Rejected ReasonNotes KDE Connect Probe installation". USB debugging and "Install via USB" were already enabled, so no toggle fix exists — do not retry ADB installs.
- Fallback used: `adb push` to `/sdcard/Download/ReasonNotes-Probe.apk` (2,267,328 bytes); on-device `sha256sum` returned exactly the frozen hash; user installed manually from file manager.
- Verified afterwards via ADB: package `org.reasonnotes.probe`, versionName 0.1 / versionCode 1, targetSdk 35, DEBUGGABLE flag, process running at check time. `org.kde.kdeconnect_tp` (KDE Connect Android) is installed on the phone.

### P2 — BLOCKED (unrooted UI anomaly)
- User observation: only "Sync now" and "Retry unconfirmed" visible; status section empty; expected controls "Choose ReasonNotes Exchange" and "Create deterministic probe note" not seen.
- Source fact (worktree HEAD, identical to the frozen APK's source): `MainActivity.onCreate` adds all four buttons unconditionally into a vertical LinearLayout inside a ScrollView — no visibility logic exists in code.
- One Qwen uiautomator capture is **not** usable as layout evidence: it captured the SystemUI/AOD window, not the app (raw dump kept at `/home/reason/Temporary/QwenProjects/ReasonNotes-scratch/ui.xml`).
- Next step must be runtime: foreground the probe app, then `adb shell dumpsys activity top` / uiautomator to inspect the real view hierarchy. Scroll position is plausible but unproven; fix UI only if a genuine defect is demonstrated.

## Confirmed routing bug — must fix before any Android→Linux test

- `android-probe/app/src/main/java/org/reasonnotes/probe/MainActivity.java` line 22:
  `KDE_DEVICE_ID = "52548cf9710c43cab2fa73ed4671aea9"` — the POCO's **own** KDE Connect ID. Wrong for Android→Linux sends.
- Correct Linux destination (M A C H I N E): `254997f28f11499d80928fe8a109749e` — user-verified on the host via `kdeconnect-cli --my-id` and certificate common name. Accept it; do not re-investigate.
- Mechanism: `KdeConnectSender.request()` launches KDC's exported ShareActivity with that value in the `deviceId` intent extra (explicit target, no chooser). Self-targeting would route to the phone itself.
- `PROBE_NOTE` UUID (line 21, dashed form of the same hex) is a deterministic note namespace: **leave unchanged**.

## Evidence boundary — nothing below has been demonstrated; do not claim it

- No successful ReasonNotes synchronization in either direction.
- SAF tree grant / persisted directory access on device: unvalidated.
- Android→Linux application transfer and Linux→Android import: undemonstrated.
- Conflict resolution (Skip / Keep Linux / Keep Android) on physical hardware: untested.
- UI anomaly root cause: unknown.

## Environment facts for immediate reuse

- Executor shell has empty PATH and no D-Bus env: always use absolute binary paths; export `DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus` before `kdeconnect-cli`.
- Host KDC state (Linux-live): paired "moto g13" `4b463f9b8cb045df8758ed50a756f747`; POCO X6 5G `52548cf9…` reachable on LAN.
- Build environment: `JAVA_HOME=/home/reason/Temporary/ReasonNotesToolchain/install/jdk-17.0.20.1+1`, SDK at `/home/reason/Temporary/ReasonNotesToolchain/install/android-sdk`, warm cache `GRADLE_USER_HOME=/home/reason/Temporary/ReasonNotesToolchain/gradle-home`; no `local.properties` in the repo, so export those variables before `./gradlew` (try `--offline` first).
- Replacement install: push new APK to `/sdcard/Download/ReasonNotes-Probe.apk`, verify on-device SHA-256 (`sha256sum` exists via toybox), user installs from file manager. The replacement will keep versionCode 1; if the installer refuses an in-place replace, `adb shell pm uninstall org.reasonnotes.probe` is safe (app currently holds no meaningful data: no SAF grant, no notes) before fresh install.

## Exact next actions for Forge

1. Foreground ReasonNotes Probe on the phone and capture the real view hierarchy (`dumpsys activity top` / uiautomator). Decide scroll-position vs layout defect; fix UI **only** if a genuine defect is demonstrated.
2. Change `MainActivity.KDE_DEVICE_ID` to `254997f28f11499d80928fe8a109749e` (keep `PROBE_NOTE` untouched) and add focused regression coverage. Host tests live in `app/src/test/java/org/reasonnotes/probe/`; making the constant package-private lets a same-package JUnit test assert it directly.
3. Rebuild: `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`. Report the new app APK SHA-256 before installation; install via the file-manager fallback and verify the package on device.
4. Resume `docs/milestone1/PHONE_TEST_PROCEDURE.md` from **P2**, one labeled action at a time.

## Do not redo / do not restart

- Accept the P0/P1 evidence above as verified: authorized device, frozen-hash chain through to manual install, package verification. No re-derivation of hashes or re-probing of the Linux KDC ID needed.
- No architecture review, no Milestone 1 reimplementation, no rerunning Forge's 61 Python tests or prior Android host test evidence.
