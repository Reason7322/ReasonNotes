# Android home notes latency and bulk deletion responsiveness — 2026-10-16

## Observable user objective

1. ReasonNotes must display existing local notes promptly on Android startup, not leave the home grid empty for ~10 seconds.
2. Bulk deletion must remain correct: selected non-conflicted notes deleted, conflicted notes skipped, immutable tombstones retained, and the entire operation should feel about one second on the real phone rather than ten.
3. Keep incoming sync/receipts, conflict preservation, pinning, search, and recoverability intact, and do not clear or modify the live installed phone data without explicit installation authorization.

## Diagnosed source paths

- `MainActivity.onCreate` performed `ProbeRepository.open` and full repository reconciliation directly on the Android main thread **before** setting its content view.
- `NoteService.listNotes` called `repository.noteIds()` and then `repository.heads(id)` for each individual note; each method reconstructed revisions from every on-disk history file. This becomes repeated full history parsing for a modest note library.
- Incoming SAF `ExchangeDirectory.scan` ran from the same serial `io` executor as note listing and deletions, and repeatedly imported already-present revision files. Duplicate import used to create/fsync a temporary history file and reconcile generated views, despite the identical immutable history existing already.
- Stored receipt reconciliation repeatedly called `repository.revision` for each receipt, which also re-read the full history. The scanner did another such lookup for each imported or duplicate revision.

## Changes in this candidate

- Initialize the initial UI before opening the repository. Open/reconcile in the existing note I/O worker. Display a clear loading state and disable write actions until the service is ready. On failure show a recoverable error rather than acting on a null service.
- `NoteService.listNotes()` groups the complete heads snapshot by note ID, preserving sorting, search behavior and conflict indicators, eliminating per-note repository reads.
- `onResume()` now requests an initial render. The incoming SAF check is triggered only **after** the notes have been applied to the home screen. Incoming checks use a separate worker, coalesce overlapping requests and continue displaying actual receipt errors rather than hiding them. When receipt scan finishes it schedules a fresh note render for any newly imported data.
- Android immutable `ProbeRepository` verifies duplicate history byte-for-byte and returns without fsync/reconcile, keeping history discrepancies an error. New imports and all write paths remain durable.
- `ReceiptStore.reconcileConfirmations()` resolves references with one history snapshot while still validating each signed-by-hash receipt against the delivery ledger. Incoming revision processing reuses each already-imported revision envelope rather than doing one full repository lookup per member. Existing duplicate evidence and receipt queuing remain idempotent.
- Prior fast bulk-delete batch, safe conflicts, and no-rewrite of unchanged derived views preserved from `877423c`.

## Verification

- Android Gradle offline `testDebugUnitTest assembleDebug`: **125/125** tests passed; APK built with original package/signature.
- Fedora suite: **194/194** tests passed; no Fedora source changes.
- Android synthetic 28 notes × 4 revisions home listing: **4 ms** on Fedora JVM. Do not extrapolate this to device performance or claim the requested one-second phone target is achieved without real-device measurements.
- Regression tests cover hundreds of history entries, search behavior, no rewriting on duplicate history, and rejection of mismatched existing immutable history bytes.

## Remaining acceptance

User must explicitly approve installation of the replacement APK. Before installation, take fresh verified private-app-data backup from connected POCO, install with `adb install -r` without clearing data, compare exact installed APK and all app data hashes. Then user should measure cold startup until local note cards appear, and deletion (using disposable notes) until the result dialog appears. Sync receipt handling also needs an actual-phone check; previously observed `confirmed revision has a different receipt` remains a reported integrity error, not suppressed by this performance work.
