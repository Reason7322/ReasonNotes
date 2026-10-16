# ReasonNotes Android sync speed + Fedora startup responsiveness, 2026-10-16

## Original observable end goal

User observed the receipt collision fixed, then reported Android sync taking approximately one minute for 86 revisions and 10 receipts, and the Fedora desktop application taking approximately a minute to open. The product remains the same offline-first native Android/Fedora note app: fast usable screens, correct note creation/editing, preserved permanent deletion when paired apps are open, reliable KDE Connect content sync, immutable history for extant notes, no false delivery confirmation and no note or private-data loss.

Acceptance: Android Start sync should normally send only newly pending revisions/receipts, not automatically resend a backlog of unconfirmed transfers; deliberate retry must remain. Outgoing planning and accepted-transfer bookkeeping should have bounded I/O even for ~100 files. Fedora should render its note list/Qt window promptly without waiting for large incoming ZIP imports, while still importing and refreshing once background receive finishes. Retain the no-background-service app-open deletion semantics. Physically measured POCO and Fedora UX are still unverified until installation and user test.

## Evidence gathered without touching live notes

- A screen capture showed 'Transfer requested for 86 revisions and 10 receipts'; this does not prove the destination imported or acknowledged the files.
- Code inspection found SyncCoordinator calling repository.revision() separately for each outgoing ID (full history graph scan each time). SyncPlanner dependency walks queried DeliveryLedger.entry() separately per revision/ancestor (entire ledger parsed per query). SyncCoordinator recorded every accepted revision/receipt individually, triggering full ledger read and fsynced replacement for each, plus one fsynced event append per revision.
- SyncActivity indiscriminately passed retryUnconfirmed=true for every ordinary Start sync, creating avoidable large retransmission and duplicate acknowledgments.
- Fedora main() called scan_inbox synchronously before constructing the Qt presenter or loading QML. Any unusually long file import therefore withheld the entire window.
- Disposable Fedora benchmarks of the current installed code: initial startup repository reconciliation ~0.03s, 119 already-imported revisions plus 10 duplicate receipts ~0.48s, cold import of the same 119 revisions ~1.02s. Offscreen Qt check-launch ~0.4-0.7s. These benchmarks do not reproduce the user-observed minute and cannot rule out KDE Connect transfer, a different device state, or real desktop graphics startup. Moving scans off the GUI critical path addresses the UI blocking independent of their duration.

## Implemented

- Android SyncPlanner uses one keyed delivery ledger snapshot for the complete outgoing dependency and confirmation analysis.
- Android SyncCoordinator materializes outgoing envelopes from one revision snapshot and records a successful share request with recordRequestedBatch() for revision ledger/audit events (one ledger fsync + one event fsync) and ReceiptStore.recordRequestedBatch() (one receipt ledger fsync). Batch input validation happens entirely before changes; attempts, retry limits, and event records remain per revision/receipt. Existing direct strict receipt-confirmation behavior is unchanged.
- Normal Android Start sync and Sync selected use retryUnconfirmed=false. Compact 'Retry unconfirmed transfers' / 'Retry unconfirmed selected notes' text action provides explicit retries and is disabled during sync/initialization. No successful transfer is inferred from merely launching KDE Connect.
- Fedora main() constructs its Qt window before submitting receive-only startup work to _IncomingWorker in the existing Qt thread pool. On completion, NotePresenter.refresh() and Qt models/signals are updated on the UI thread. A lock serializes startup receiving with explicit sync to avoid concurrent delivery/receipt ledger changes. Headless --check-launch remains a synchronous deterministic test mode, preserving existing install smoke contracts.
- No installed app, user notes, or transfers were modified in the development workflow.

## Validation

- Android offline testDebugUnitTest assembleDebug: 135 tests passing, including 86-revision/10-receipt sync (11 ms on host JVM for planning + record bookkeeping, not on a real POCO), batch event durability/idempotence and invalid-mixed-batch validation, and default no-resend vs explicit retry with attempt counts.
- Fedora Python/Qt tests: 203 passing, including a deliberately blocked incoming worker that leaves the QML window responsive and refreshes its note list after remote import.
- Worktree build and commit ready; user authorization required before in-place Android update or Fedora launcher switch, followed by actual device timing and receipt-confirmation validation.
- Existing confirmed receipts must never be rewritten; pending is not confirmed. Old KDE Connect file arrivals can still take time; large transfer duration depends on carrier and receiver.
