# POCO X6 5G Acceptance Procedure

## Frozen artifacts

- Source commit: `926099ec1f28fc7b08eabd44b1b5c2a6e58e8491`
- APK: `android-probe/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: `ef19db85b723b6e495759cb5cb48313157801f20fc3f4260b4f1703d612f1c90`
- Python: 3.14.8
- Linux KDE Connect: 26.08.1
- Paired/reachable POCO device ID from Linux: `52548cf9710c43cab2fa73ed4671aea9`
- Build: `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest` with the project-external JDK 17 and Android 35 SDK documented in the evidence matrix.

Only generated ReasonNotes fixtures and the dedicated `ReasonNotes Exchange` directory may be used.

## Interaction protocol

Run one labeled action at a time. After each user action, Forge inspects ADB output, app state, Linux event logs, hashes, and repository heads before requesting the next action.

### P0 — connect the test device

User action: connect the POCO by USB, enable **Developer options → USB debugging**, and accept the computer authorization prompt. Do not select any general storage folder.

Expected observation: `adb devices -l` shows one authorized POCO. This proves only debugging connectivity.

### P1 — install the frozen APK

Forge action after P0: verify the APK SHA-256 again, then run `adb install -r` on that exact artifact.

User action if Android asks: approve installation of this debug APK.

Expected observation: package `org.reasonnotes.probe` is installed. Installation is not synchronization proof.

### P2 — grant only the isolated exchange directory

User action in ReasonNotes Probe:

1. Tap **Choose ReasonNotes Exchange**.
2. Create or select a directory named `ReasonNotes Exchange`.
3. Approve read/write access to that directory only.

User action in KDE Connect Android Share settings: set its incoming-file destination to the same `ReasonNotes Exchange` directory.

Expected observation: reopening/resuming the probe reports a scan, and the persisted-tree instrumentation test can reopen the same URI. No per-file file-manager import is used.

### P3 — verify Android integration without claiming transfer

Forge action: run the SAF and KDE Connect intent-resolution instrumentation tests.

Expected observations:

- the saved tree grant persists;
- valid Markdown imports and a partial envelope is rejected;
- unrelated files are ignored;
- package `org.kde.kdeconnect_tp` and exported `ShareActivity` resolve;
- the content URI opens under temporary read permission.

This proves intent/storage integration only. It does not prove that KDE Connect transferred a file.

### P4 — non-conflicting Linux to Android leg

Forge actions:

1. Create one deterministic Linux fixture in an isolated repository.
2. Start the Linux receive listener.
3. Run Linux `sync`, which scans first and then requests the fixture transfer.

User action: open/resume ReasonNotes Probe and tap **Sync now** once.

Expected observations: Android displays the exact Linux revision ID and body after scanning, with no file-manager action. Record whether KDE Connect opened directly, briefly foregrounded, or showed a chooser.

### P5 — Android to Linux leg and convergence check

User action: tap **Create deterministic probe note**, then tap **Sync now** once.

Forge action: inspect the Linux `shareReceived` signal/import, or perform one recovery scan if the listener missed it.

Expected observations: Linux contains the Android revision only after durable import. Compare complete head IDs and body SHA-256 values on both devices. Mark convergence only if both sets and bodies match.

### P6 — labeled safety matrix

Run each fixture separately and inspect after each action:

1. renamed duplicate;
2. offline/unreachable target and explicit retry count;
3. interrupted larger transfer if practical;
4. same-note offline conflict with both labeled previews;
5. Skip, with unchanged history and heads;
6. Keep Linux and multi-parent convergence;
7. reset fixture, Keep Android and multi-parent convergence;
8. edit versus deletion, resolved in each direction;
9. stale resolution plus a third head;
10. late ancestor after resolution.

Every observed result is recorded as `Linux-live`, `Android-user-observed`, `failed`, or `pending`. A request, receiver notification, and ReasonNotes import remain separate events.

