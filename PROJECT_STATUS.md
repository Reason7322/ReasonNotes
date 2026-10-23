# ReasonNotes — Project Status

## Android receipt sender repair — 2026-10-23

The first physical JSON v1 receipt return exposed a concrete Android sender
failure: KDE Connect's asynchronous transfer code lacked effective read access
to the non-exported ReasonNotes `FileProvider` URI, despite the share intent's
flag and `ClipData`. The generated carrier contained only the intended receipt,
but it did not reach Fedora.

`KdeConnectSender.request()` now grants KDE Connect's package temporary,
read-only access before launching the explicit share activity and revokes that
grant after five minutes or immediately on launch failure. The provider remains
non-exported; no broad storage access, protocol, queue, receipt, or ledger
change was introduced. Focused sender/transport/receipt verification passed
36 tests, and both the debug application and instrumentation-test APKs built.

The repaired APK is staged at
`/home/reason/Temporary/ReasonNotes-JSON-v1-Candidates/android-receipt-grant-repair/ReasonNotes-Android-JSON-v1-receipt-grant.apk`,
SHA-256 `e0e1f8e6eb2c8d184f90aaa59f9bfc0625a1aa3a6aa5ae9cbbda56c7b5ea81ec`.
It was not installed or run. The pending physical receipt and existing attempt
history remain for a separately authorized in-place update and single retry.
See `docs/ANDROID_RECEIPT_SENDER_REPAIR_2026-10-23.md`.

## Current milestone

Milestone 1 is complete. Milestone 2 Tasks 1 through 4 are implemented at source/test/build level. Task 4 still awaits physical and visual acceptance:

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

The sidebar now also uses Lucide SVG icons for Save, Sync,
and Delete; the New Note action is unchanged.

The Milestone 2 receipt path has focused automated verification but has not
been physically synchronized on the POCO. The desktop Concept C interface
received owner visual feedback on the real Wayland desktop, but full
interactive Sync/conflict acceptance remains pending.

Android Task 4 now implements native notes Home, Editor, Settings, conflict
resolution, local-only pinning, guarded immutable writes, draft recovery,
selective outgoing synchronization, and receipt-aware Sync UI. Sol completed
the final host suite (93/93) and assembled a candidate APK from Forge's
`6171312` code checkpoint. See `docs/milestone2/ANDROID_TASK4.md` for the
verified hash, signing fingerprint, packaging checks, and physical acceptance
restrictions. Subsequently, the owner explicitly authorized an in-place
installation on 2026-10-15, and ADB returned Success; the installed APK's
SHA-256 matched the candidate exactly. The owner confirmed the Android Home,
Editor, and Sync UI screens launch and show existing test notes. Cosmetic
alignment issues remain and actual receipt/convergence acceptance is pending.

## Current branch and provenance

- Latest Android implementation: `forge/android-notes-shell` at
  `6171312f54386e0f09203ab84199861591d95071`.
- Task 4 finalization: `sol/android-task4-finalize` (documentation-only
  successor to the implementation SHA; see final neutral Git ref).
- Historic Forge desktop branch: `forge/kdeconnect-feasibility`.
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

Sol/ChatGPT completed the explicitly authorized Task 4 verification,
APK packaging evidence, and checkpoint documentation from a separate
worktree. Forge's and Qwen's worktrees and branches were not modified.
Next ownership is unassigned until the owner authorizes physical acceptance
or the separate theme milestone.

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
  or visible desktop was used for that earlier checkpoint. The owner has since
  inspected the refined desktop interface on real Wayland; full interactive
  Save/Sync/conflict acceptance is still pending.
- Refinement command:
  `QT_QPA_PLATFORM=offscreen PYTHONPATH=src python3 -m unittest tests.test_qml_smoke tests.test_linux_presenter -v`
  passed 28/28. The reproducible renderer completed without QML warnings at
  both geometries; the inspected PNG SHA-256 values were
  `e7df4515657eee472c87fa202353f162308b5c09974d036af603f6a637e85b83`
  (2048×1152) and
  `fa038791f7e6b7cac53f307441bf84c94c121ddc5f091828ef6c0cee7b9ef8da`
  (900×600).
- Task 4 finalization on `6171312f54386e0f09203ab84199861591d95071`:
  `./gradlew --offline --no-daemon testDebugUnitTest` — 93/93 tests passed
  (23 suite reports, 0 failed/errored/skipped); `assembleDebug` — success.
  Package `org.reasonnotes.probe`, version 1 (0.1), min SDK 26, target SDK 35;
  APK SHA-256 `07fe3beea73d5af4c4a497a6998cca5d02bd5d4774c7dcab5807a1a7a0eec752`.
  Preserved at `/home/reason/Temporary/ReasonNotes-Task4-Finalize-artifacts/ReasonNotes-Android-Task4-6171312-07fe3bee.apk`.
  The candidate and preserved Milestone 1 reference APK share signing certificate
  SHA-256 `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147`.
  The installed phone package's certificate has **not** been checked.
- These are automated results. No physical POCO receipt-runtime claim is made.

## Current architecture

KDE Connect remains the proven transport. Immutable revision history in
each device's repository remains the durable note-content source; generated
views and application summaries are rebuildable. Receipts remain separate
transport evidence and never alter note ancestry or conflict selection. The
Qt presenter and Android native service contain no second synchronized note
store. Both platforms implement selected outgoing note revision filtering,
while incoming scans and required receipts remain unconditional. Android
DraftStore and PinStore are device-local metadata, not synchronized history.

## Latest development checkpoint — canonical JSON v1 on modern Android

Forge integrated the approved canonical JSON v1 carrier on a fresh branch
created from the recovered modern Android source
`9e2cd049282a31b1f9db45bd3ec09b6da9bc5721`. The implementation sends every
non-empty plan, including a one-envelope plan, as one deterministic
content-addressed JSON payload. Android accepts canonical JSON bundles plus
legacy individual `.md` envelopes; it does not create or import ZIP carriers.

The full carrier, every embedded envelope, ordering, schema, payload hash, and
declared identity are validated before repository or receipt-ledger mutation.
Replayed carriers remain per-envelope duplicates, and a malformed later member
cannot confirm an earlier receipt. Existing receipt confirmation, retry,
history, conflict, deletion, and modern UI behavior remain the authority.

Clean offline verification passed 157/157 JVM unit tests, `assembleDebug`, and
`assembleDebugAndroidTest`. Candidate APK SHA-256 is
`e7cbf93bb9f3e1821d361a4b32cd50681b2c2350b12d5eac7339911e1bb9ee97`.
No APK was installed, no ADB operation was performed, and physical durable
round-trip acceptance remains pending separate authorization.

### Android JSON v1 launcher restoration candidate — 2026-10-23

The modern JSON v1 branch now restores the exact accepted recovery launcher
resource at `android-probe/app/src/main/res/drawable/reasonnotes_launcher.xml`
and references it as both `android:icon` and `android:roundIcon`. The resource
is byte-identical to the protected recovery checkout copy (SHA-256
`12f95baf8b1feed12b3d96890f59f8b5c3bb30c83b772dc0297e90fb42306e36`).

Focused verification ran only the launcher contract and canonical JSON carrier
tests, then assembled the debug APK; the earlier 157-test result was not
repeated. `aapt` reports package `org.reasonnotes.probe`, version 1 / 0.1,
min SDK 26, target/compile SDK 35, launch activity
`org.reasonnotes.probe.MainActivity`, and packaged icon
`res/drawable/reasonnotes_launcher.xml`. The candidate and the known-good
recovery APK both verify with APK Signature Scheme v2 and certificate SHA-256
`d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147`.

Prepared APK:
`/home/reason/Temporary/ReasonNotes-JSON-v1-Candidates/android/ReasonNotes-Android-JSON-v1-with-launcher.apk`
(SHA-256 `feb2312efa2195ed739b7d434b809bc169106bfaae7287d8039a7878f832b5fa`).
It was not installed or launched; no ADB command or phone/data mutation was
performed. Physical Fedora/Android synchronization remains the next potential
owner-authorized acceptance step, not part of this milestone.

## Latest development checkpoint — native Fedora desktop launch

The owner requested desktop usability and ordinary launch instead of more
synchronization research. The Qt Quick/PySide6 desktop app now opens without
developer-only arguments, uses an isolated XDG user note repository, discovers
exactly one reachable KDE Connect peer if possible, and starts without phone
connectivity. A native user-level Fedora launcher, .desktop entry, and scalable
icon are provided; data remains outside versioned installed application code.
Native Hyprland/Wayland smoke test and Fedora tests pass. No Android APK,
sync protocol, existing user notes or test history was altered in this scope.
See `docs/milestone2/FEDORA_NATIVE_DESKTOP.md`.

## Previous development checkpoint — single-carrier KDE Connect transfer

The owner approved an isolated repair after the physical Android sync
successfully scheduled 21 receipt transfers but Fedora observed only one file
arriving. Instead of making 21 unconfirmed share-activity launches, Android
now prepares one canonical, SHA-256-addressed ZIP carrying all selected note
revisions and outstanding receipts. Fedora's opt-in received-directory scanner
validates every member before import, preserving individual receipt validation
and not treating a share request as delivery confirmation.

**162/162 Fedora tests and 109/109 Android tests passed**. The actual
compiled Android Java bundle generator created a canonical ZIP containing two
test revisions that the Fedora CLI imported successfully, queuing their
receipts; an idempotent rescan recognized both as duplicates.
A new signed APK has been built and preserved but **not installed**.
The physical multi-receipt transfer remains an **unverified acceptance gate**.
See `docs/milestone2/BUNDLED_KDE_CONNECT_TRANSPORT.md` for details.

## Previous development checkpoint — Android ledger merge repair

The owner authorized an isolated fix after installing the previous peer-identity
APK revealed a **real mixed physical/logical delivery-record conflict** on the
POCO. The safe migration refused to overwrite either row, and opening the
Android Sync screen could not complete. The new Sol branch
`sol/android-peer-ledger-merge` conservatively reconciles unconfirmed duplicate
entries (maximum attempts, latest request timestamp, strongest safe state),
preserves authentic confirmation receipts, and still fails atomically on
contradictory confirmations.

**102/102 Android tests and 152/152 Fedora tests passed**. The owner
subsequently approved an in-place installation of that ledger-merge APK and
read-only backup of its old ledger/event/receipt records, all verified by
matching SHA-256 checksums. Opening Sync successfully reduced 19 physical
and logical rows to 18 logical peer entries, preserving all revision IDs,
attempts, warning states and original event history. A later selected-note
sync recorded one transfer request for each of 21 queued receipts but only
one receipt file was observed arriving on Fedora. See
`docs/milestone2/ANDROID_LEDGER_MERGE_REPAIR.md` for the preceding
code-only checkpoint and `docs/milestone2/BUNDLED_KDE_CONNECT_TRANSPORT.md`
for the new transport repair. End-to-end physical receipt confirmation
remains unverified.

## Previous development checkpoint — logical peer and received-name collisions

The owner authorized targeted fixes after real physical receipt tests identified
a protocol identity mismatch. The follow-up Sol branch
`sol/android-peer-and-receive-collisions` separates Android's physical KDE
Connect device ID from the logical `linux` revision/receipt peer; it safely
migrates older delivery ledger keys in one checked atomic update. The Linux
Downloads receiver also understands KDE Connect ` (1).md` collisions for
revision and receipt envelopes.

**152/152 Fedora tests and 98/98 Android tests passed**; a replacement APK
was built, its certificate matched the installed debug signer, and a copy of
the exact collision-named received revision was imported in an isolated Fedora
repository. The owner subsequently authorized an in-place update of **that earlier
candidate**, and its APK bytes were verified on the POCO. Opening its Sync
screen exposed a mixed-ledger conflict. The subsequent ledger-merge APK
was later installed with separate authorization and its ledger migration
verified; remaining incomplete multi-file delivery motivated the new bundle
transport repair. See `docs/milestone2/ANDROID_PEER_RECEIPT_FIX.md` for the
previous artifact and data-migration safeguards.

## Historical Linux receiver checkpoint

A separate Sol-owned isolated Linux receiving fix adds opt-in scanning of a
second KDE Connect receive folder (such as Downloads) and recognition of
extensionless timestamp-named Android envelopes. **150/150** Python tests passed,
and copies of three real Android transfers imported into a disposable
repository with three receipts queued. The other eight transfers reported as
requested by Android were not observed arriving; the sender-side outcome
remains unconfirmed. See `docs/milestone2/KDECONNECT_RECEIVE_TRIAGE.md`.
This fix has not been launched against live data or deployed to a running
Fedora application; the next acceptance step remains separate and owner-gated.

Android Task 4 is built and installed on the POCO with owner authorization;
the installed APK hash matches the Task 4 candidate. The owner reviewed the
Home, Editor, and Sync screens and confirmed basic functionality, with minor
UI alignment issues. The remaining product gate is owner-approved controlled
two-device synchronization/receipt convergence testing with disposable notes.
Do not treat the successful installation or Android share-activity requests as
proof of durable Fedora import or Android-side receipt confirmation. Neither
live note data nor the existing phone application should be reset, cleared,
or uninstalled to work around an issue. White/Dark/LiquidGlass and cross-device
pin synchronization remain separate future tasks.

## Important constraints

- Forge owns `/home/reason/Codex-Projects/ReasonNotes`; Qwen owns
  `/home/reason/Temporary/QwenProjects/ReasonNotes`.
- Keep transfer requests, KDE Connect completion, ReasonNotes import, and future
  receipt confirmation as separate evidence.
- Never discard conflicting revisions or select a winner by timestamp.
