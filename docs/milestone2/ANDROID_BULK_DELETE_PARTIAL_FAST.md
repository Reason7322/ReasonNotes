# Android bulk-delete correction and latency repair — 2026-10-16

## User acceptance criteria

- Long-press selection remains available.
- If 15 notes are selected and exactly one has an unresolved conflict, delete all 14 non-conflicted notes, **skip** that one, and explicitly report 14 deleted / 1 skipped. The conflict is preserved, not silently resolved.
- Notes already deleted, missing, or with incomplete ancestry are skipped individually. Unexpected durable-write failures report affected notes and must not be misreported as completed deletions.
- Every successful note deletion remains an individual, recoverable immutable tombstone eligible for existing explicit synchronization. No automatic network transfer and no direct removal of history.
- The deletion operation should feel approximately one second on the POCO rather than ten; measure physically before claiming the latency target is met.
- No regression to pinning, note display, incoming resume scans, Android package identity, or Fedora desktop.

## Repair

The original implementation aborted the **entire selection** if any note conflicted. It also called `repository.heads()` separately for every note then `service.deleteNote()` per note; that path rebuilt all immutable history and rewrote the index and every generated current view for each individual deletion.

`ProbeRepository.deleteNotes()` now owns one lock for the batch, loads history and builds a revision graph **once**, checks each selected note separately for exactly one live complete head, writes an individual durable tombstone for every eligible note, and reconciles generated views **once**. The generic reconciler byte-checks its index and current views and skips rewriting unchanged data; it still restores missing working files from immutable history on restart.

`BulkNoteDeletion` maps the batch result to the screen; the new completion dialog gives the actual deleted, skipped and failed counts and leaves skipped/failed notes selected. `PinStore.clearPins()` clears all successfully deleted pin IDs in one preference update instead of one update per note. Unexpected errors are caught and presented instead of stranding the UI in deletion-in-progress mode.

## Verification

Offline Android Gradle `testDebugUnitTest assembleDebug`: **123/123 passed**, no errors, failures or skips; APK assembled. The 15-note/one-conflict integration-style JVM test observed **10 ms** on the Fedora host. That benchmark is not a physical Android timing guarantee.

Fedora Python/Qt suite: **194/194 passed**. No Fedora production code changed.

Installed POCO application remains untouched in this checkpoint. The previously verified 217-file private backup from 2026-10-16 is preserved under `artifacts/AndroidBulkDelete/phone-backups`; obtain explicit authorization before any new APK installation, and preserve existing user data and signing identity.
