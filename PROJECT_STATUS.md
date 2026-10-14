# ReasonNotes — Project Status

## Current milestone

Milestone 1 is complete. Milestone 2 Tasks 1 and 2 are implemented:

- Durable one-revision import receipts distinguish transfer request, KDE
  Connect completion, durable import, and application confirmation without
  putting transport metadata into immutable note history.
- The Python note application service provides create, edit, tombstone
  deletion, coalesced unchanged saves, Unicode-aware search, conflict
  inspection and explicit resolution, stale-choice rejection, and
  history-derived restart recovery.

The Milestone 2 receipt path has focused automated verification but has not
been physically synchronized on the POCO. The GTK and Android note interfaces
have not been started.

## Current branch and provenance

- Active branch: `forge/kdeconnect-feasibility`
- Received Qwen acceptance checkpoint:
  `73710b088fb0d8f167a054916fef57ffc78a233c`
- Android routing fix:
  `cbb7c2d5c26e28ed3601b5ae0b37618a34f2e6c1`
- Android system-chrome layout fix:
  `bf5460fd03c2a050d34d8e478b44abe16e7ab747`
- Multi-note Android probe display:
  `7d9c597ee68720b7ac0f547f2f320123990a24ed`
- Conflict-resolution outbox fix:
  `0482711e0a4db132c7608a98358fa0b630806e47`
- Durable import receipts:
  `8a26d6f74438acd781d50db10278b19d835c6c6b`
- Initial Python note application service:
  `c99987a000ddd48f727c27ecba9eea14df619342`

## Current owner

Forge. Qwen's worktree and branch were not modified.

## Physical acceptance result

- **P0/P1 — PASS with installation workaround:** POCO X6 5G, Android 16,
  authorized through ADB. HyperOS rejected `adb install -r` with
  `INSTALL_FAILED_USER_RESTRICTED`; the user installed the verified APK through
  the file manager.
- **P2 — PASS:** the missing controls were physically present but covered by
  Android 16's action bar. The corrected layout was verified on-device. The
  user granted the dedicated `ReasonNotes Exchange` SAF directory and set it as
  KDE Connect Android's incoming destination.
- **P3 — PASS by equivalent direct evidence:** persisted SAF access, valid
  import, truncated-file quarantine, unrelated-file ignore, exported KDE Share
  activity, and content access were verified on the phone. The instrumentation
  runner itself was not installed because of the HyperOS restriction.
- **P4 — PASS:** Linux-to-Android transfer arrived automatically with no
  chooser or file-manager operation and imported with the exact revision ID,
  envelope hash, and body hash.
- **P5 — PASS:** Android-to-Linux direct targeting produced a KDE Connect
  completion event and a separate durable ReasonNotes import. Complete heads
  and body hashes matched on both devices.
- **P6 — PASS for tested cases:** duplicate delivery, default no-resend,
  bounded explicit retry, offline failure, labeled conflict previews, Skip,
  Keep Linux, Keep Android, edit-versus-deletion in both directions,
  stale-resolution protection, children-before-parent delivery, and late
  ancestor reconciliation were physically verified. Superseded revisions
  remained recoverable.

Full IDs, hashes, and evidence boundaries are in
`docs/milestone1/PHONE_TEST_RESULTS.md`.

## Tested APK

- Source: `7d9c597ee68720b7ac0f547f2f320123990a24ed`
- Path:
  `/home/reason/Codex-Projects/ReasonNotes-artifacts/ReasonNotes-Probe-multinote-7d9c597-31002579.apk`
- SHA-256:
  `310025791599f0c226cfaef5f59f6d143d2cdf23578b3436927165d60936d03e`
- Correct Android outgoing peer:
  `254997f28f11499d80928fe8a109749e` (Linux)

The original APK remains frozen and preserved with SHA-256
`ef19db85b723b6e495759cb5cb48313157801f20fc3f4260b4f1703d612f1c90`.

## Milestone 1 final verification

- Python: `PYTHONPATH=src python3 -m unittest discover -s tests -v` —
  61/61 passed.
- Android: `testDebugUnitTest` — 17/17 passed.
- Android builds: `assembleDebug` and `assembleDebugAndroidTest` — passed.
- Rebuilt app APK SHA-256 exactly matches the installed acceptance artifact:
  `310025791599f0c226cfaef5f59f6d143d2cdf23578b3436927165d60936d03e`.

## Milestone 2 verification

- Task 1 retained exact-implementation evidence: focused Python
  receipt/delivery/sync/conflict tests passed 39/39.
- Task 1 retained Gradle XML: `ReceiptEnvelopeTest` passed 3/3,
  `ReceiptStoreTest` passed 5/5, and `DeliveryLedgerTest` passed 6/6.
- Task 2:
  `PYTHONPATH=src python3 -m unittest tests.test_note_service tests.test_repository tests.test_conflicts tests.test_sync -v`
  passed 37/37, including deterministic remote-edit, remote-deletion, and
  history-before-generated-view interleavings.
- These are automated results. No physical POCO receipt-runtime claim is made.

## Current architecture

KDE Connect remains the proven transport. Immutable revision history in
`Repository` is the only durable note-content source; generated views and
application summaries are rebuildable. Receipts remain separate transport
evidence and never alter note ancestry or conflict selection.

## Next action

Proceed with Task 3 in
`docs/superpowers/plans/2026-10-12-milestone2-minimal-notes-slice.md`: the
Fedora presenter and minimal GTK 4 shell. Preserve the tested NoteService and
keep GTK callbacks thin. Do not claim receipt confirmation on the POCO until
that path receives separate physical runtime verification.

## Important constraints

- Forge owns `/home/reason/Codex-Projects/ReasonNotes`; Qwen owns
  `/home/reason/Temporary/QwenProjects/ReasonNotes`.
- Keep transfer requests, KDE Connect completion, ReasonNotes import, and future
  receipt confirmation as separate evidence.
- Never discard conflicting revisions or select a winner by timestamp.
