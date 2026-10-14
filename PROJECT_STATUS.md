# ReasonNotes — Project Status

## Current milestone

Milestone 1 is complete. Milestone 2 Tasks 1 through 3 are implemented:

- Durable one-revision import receipts distinguish transfer request, KDE
  Connect completion, durable import, and application confirmation without
  putting transport metadata into immutable note history.
- The Python note application service provides create, edit, tombstone
  deletion, coalesced unchanged saves, Unicode-aware search, conflict
  inspection and explicit resolution, stale-choice rejection, and
  history-derived restart recovery.
- The Fedora desktop application is a Qt 6 / Qt Quick interface backed by a
  thin PySide6 bridge and the existing `NoteService`. It provides draft-safe
  editing and autosave, search, deletion, asynchronous synchronization,
  evidence-accurate delivery status, selective outgoing synchronization, and
  full immutable-history conflict comparison and resolution.
- The desktop visual shell now follows the owner-approved Concept C — Minimal
  Writing direction: a 300-pixel resizable sidebar, centered 920-pixel writing
  column, centralized dark/gold theme, stronger typography and spacing,
  accurate local/phone status, responsive empty/editor states, and matching
  Sync and conflict surfaces.

A coordinator-owned follow-up branch `sol/editable-titles` makes the
Concept C heading directly editable and separates title/body editing while
retaining the single Markdown document in immutable history. The sidebar
now shows note titles only. The original Forge checkpoint remains untouched.

The sidebar now also uses consistent local vector icons for Save, Sync,
and Delete; the New Note action is unchanged.

The Milestone 2 receipt path has focused automated verification but has not
been physically synchronized on the POCO. The Qt interface has passed
offscreen component loading but has not received visible graphical or
interactive owner acceptance. The Android Task 4 note interface has not been
started.

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
- Task 2 accepted checkpoint:
  `1c413fa09292d8f0eac944c0c6ce8c22c98ed906`
- Task 3 Qt Quick design checkpoint:
  `de63f2f657585162767b413ab774408955b0cdfd`
- Task 3 accepted functional checkpoint:
  `caca06404d8b0994900714b2bf1a24923587a2dd`

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
- Task 3:
  `QT_QPA_PLATFORM=offscreen PYTHONPATH=src python3 -m unittest tests.test_selective_sync tests.test_linux_presenter tests.test_qml_smoke tests.test_note_service tests.test_sync tests.test_delivery tests.test_receipt tests.test_conflicts tests.test_cli -v`
  passed 91/91. All test repositories and KDE Connect adapters were
  disposable or mocked.
- The real `Main.qml` component tree loaded offscreen without warnings using
  PySide6 6.11.2 and Qt 6.11.2. The smoke test also instantiated a populated
  note delegate, a long scrollable editor, and selected-note sync controls.
  Review regressions cover unbound drafts, deletion-head resolution,
  concurrent autosave/import conflicts, and a sync catalogue independent of
  sidebar search, including tombstones. This is component-wiring evidence,
  not visual or interactive acceptance.
- The Concept C refinement has focused QML and presenter verification. The QML
  suite covers exact theme values, hierarchy and click-target geometry,
  persisted/default sidebar sizing, centered 920-pixel and responsive smaller
  writing layouts, empty and scrolling editor states, secondary view styling,
  and preserved sync-mode exclusivity. Deterministic offscreen renders were
  inspected at 2048×1152 and 900×600 using disposable notes; no live note data
  or visible desktop was used. Owner visual acceptance on a real Wayland
  display remains pending.
- Refinement command:
  `QT_QPA_PLATFORM=offscreen PYTHONPATH=src python3 -m unittest tests.test_qml_smoke tests.test_linux_presenter -v`
  passed 28/28. The reproducible renderer completed without QML warnings at
  both geometries; the inspected PNG SHA-256 values were
  `e7df4515657eee472c87fa202353f162308b5c09974d036af603f6a637e85b83`
  (2048×1152) and
  `fa038791f7e6b7cac53f307441bf84c94c121ddc5f091828ef6c0cee7b9ef8da`
  (900×600).
- These are automated results. No physical POCO receipt-runtime claim is made.

## Current architecture

KDE Connect remains the proven transport. Immutable revision history in
`Repository` is the only durable note-content source; generated views and
application summaries are rebuildable. Receipts remain separate transport
evidence and never alter note ancestry or conflict selection. The Qt presenter
and bridge contain no second note store. Selective sync filters ordinary
Fedora outgoing note revisions only; incoming scanning and receipts remain
unconditional.

## Next action

Task 3 stops after the Concept C refinement checkpoint. The next product gate
is owner-approved visible and interactive Qt desktop acceptance against a
disposable repository. Task 4 is not started and requires a new assignment.
Do not claim receipt confirmation on the POCO until that path receives
separate physical runtime verification.

## Important constraints

- Forge owns `/home/reason/Codex-Projects/ReasonNotes`; Qwen owns
  `/home/reason/Temporary/QwenProjects/ReasonNotes`.
- Keep transfer requests, KDE Connect completion, ReasonNotes import, and future
  receipt confirmation as separate evidence.
- Never discard conflicting revisions or select a winner by timestamp.
