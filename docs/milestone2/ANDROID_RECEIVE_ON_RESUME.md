# Android receive-only synchronization on home-screen resume — 2026-10-15

## Observed problem

The user sent selected **PC Note** revisions from the Fedora Qt desktop app at 20:01:55 local. Fedora's real delivery ledger recorded **four transfer requests** addressed to `android`, attempts 1, but no Android acknowledgement had returned. KDE Connect showed an outgoing transfer progress bar; that alone is not evidence that Android accepted the note.

Existing Android `ExchangeDirectory.scan` already validates and durably imports canonical incoming `.md` revisions and receipts, and queues per-revision acknowledgements. Previously it ran **only inside `SyncActivity` when the user explicitly started synchronization**. `MainActivity.onResume` only rerendered the cached library. Consequently Android could have received the files but still display no imported notes.

## Narrow implementation

Base: `7156767db339c1a7519e1e60afa3033f2931c5fa`.
Worktree: `ChatGPTProjects/ReasonNotes/worktrees/AndroidReceiveOnResume`.

- `MainActivity.onResume()` now schedules a **receive-only, asynchronous** check of the user-configured Storage Access Framework `ReasonNotes Exchange` directory. It uses the existing single-threaded `io` executor, never the UI thread for disk/document imports.
- The new `IncomingReceive.scanPhone` calls the already-tested `ExchangeDirectory.scan` using the current private `ProbeRepository`, three-attempt `DeliveryLedger`, existing `ReceiptStore` and logical peer migration. Existing revision/receipt identity validation and durable ledger invariants are reused rather than bypassed.
- When scanning completes, the home note list is reloaded and the status footer shows receipt/import counts or the first error; if no exchange directory is configured, notes still open offline normally and no import request runs.
- No service, observer, alarm, WorkManager, background polling or persistent network activity was added. It runs on **home-screen foreground/resume**, not automatically while the app stays open indefinitely in the foreground. A note received while the home screen has remained continuously visible may need the app to leave and return to the foreground.
- Opening the home screen **does not call `SyncCoordinator.synchronize`, `KdeConnectSender`, or an outbound transfer**, and does not increment send/retry attempt counters. Acknowledgment receipts are only **queued**, not transmitted automatically; outbound receipt delivery remains an explicit user action.

## Validation

- New JVM unit tests cover a Fedora-origin canonical `PC Note` revision imported durably, exactly one queued receipt to `linux`, the unchanged pending/zero-attempt state, idempotent repeated reception, and status reporting for confirmation and permission errors.
- **112/112 Android `testDebugUnitTest` tests passed**, with 0 failures, errors or skips.
- **172/172 Fedora Python/QML tests passed**; no Fedora source changed.
- `assembleDebug` succeeded using the project-local Android SDK/JDK under `ChatGPTProjects/ReasonNotes/toolchain/Android`.
- Package ID `org.reasonnotes.probe`, debug signer certificate SHA-256 `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147` (matches previously installed debug-signed build).
- Candidate APK is debug-signed. Candidate APK SHA-256 `344b418ad2a8cb68924a68cdfa8eb02effc3b18c1b43b5a801cd38269a003cbe`, 2,349,904 bytes.
- APK candidate belongs under `ChatGPTProjects/ReasonNotes/artifacts/AndroidReceiveOnResume`. **Not installed.**

## Physical acceptance gate

USB ADB showed zero attached devices on 2026-10-15 at inspection. The phone's latest transfer contents and receipt ledger therefore could not be inspected. The real KDE Connect transfer may or may not have physically arrived in the configured exchange folder; a progress bar is not proof.

After owner explicitly approves in-place installation:

1. Read-only verify protected Android ledgers and existing notes; take a fresh matching-hash backup before `adb install -r`.
2. Install exactly the saved signed candidate APK without clearing app data; verify installed SHA and preserved ledgers.
3. Open ReasonNotes on Android or background/foreground the home screen **without pressing Sync**.
4. Confirm `PC Note` appears if the four sent `.md` revisions are present in the saved exchange tree; verify immutable IDs, receipt queue entries and no increase in outgoing transfer attempts.
5. If not received, locate KDE Connect's actual phone download location before attempting any retry. Do not invent a confirmation and do not transfer additional note data automatically.

This fix addresses **receiving files already in the configured Android exchange folder**. It does not guarantee that KDE Connect delivered all files or automatically place arbitrary downloads outside the selected tree into that folder.
