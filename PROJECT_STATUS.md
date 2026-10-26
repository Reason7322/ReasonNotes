# ReasonNotes — Project Status

## Historical malformed-file isolation repair (2026-10-26)

After the direct receive-path repair imported the disposable resolution and
queued its exact acknowledgment, the next selected sync still failed before
requesting KDE Connect. Read-only comparison found exactly one unprocessed file
among 405 shared exchange files: the intentionally incomplete historical
fixture `reasonnotes-partial.md`. Its repeated parse error was treated as a
global scan failure and blocked the independent valid acknowledgment.

The direct scanner now records a completely read malformed file in the durable
processed index after reporting its rejection once; the original file remains
untouched for evidence. Current-state synchronization treats filename-scoped
rejections (`<filename>: <error>`) as isolated warnings and continues valid
independent outgoing work. Missing or unreadable exchange storage remains a
fatal pre-send error. Focused RED-to-GREEN tests cover both guarantees and the
debug APK build succeeds. Replacement APK SHA-256:
`1a0b6585de76eac3cc35a09867588b25b008ecc79890d0bdd331dc0d5901ed20`.
It has not been installed. The 348-byte acknowledgment remains queued and
Fedora remains pending; no retry occurred after this source repair.

## Sync-screen direct receive-path repair (2026-10-26)

Physical testing of the manual readiness candidate exposed a wiring regression:
KDE Connect delivered the selected 718-byte carrier to the shared
`ReasonNotes Exchange` directory, but `SyncActivity` bypassed
`IncomingReceive.scanPhone(...)` and called the stale SAF scanner directly.
The home screen already used the repaired direct reader. Consequently the Sync
screen did not see the carrier, queued no acknowledgment, and incorrectly
returned quickly with no useful work while Fedora remained pending.

`SyncActivity` now uses the same current-state incoming entry point as the home
screen. When Android has All files access, that entry point reads only the
dedicated shared exchange directory directly; its tested SAF fallback remains
for environments without that grant. Focused RED-to-GREEN verification covered
the SyncActivity contract, `IncomingReceive`, and current-state synchronization,
followed by a successful `assembleDebug`. The replacement offline APK SHA-256
is `45eabdf14836872b8afbd59d8ff97922145040edf0fe1fc43b4b0cf951468fd6`.
It has not been installed. The previously delivered disposable carrier and
Fedora pending resolution remain preserved for a separately authorized retry.

## Android KDE Connect manual readiness gate (2026-10-26)

Android user-initiated Sync and Retry actions now open the installed KDE
Connect application before ReasonNotes creates a carrier or changes transfer
state. When the owner returns, ReasonNotes asks whether the Fedora computer is
visible and connected. Only **Yes, continue** resumes the exact all-notes or
selected-note request; **Not yet**, dialog cancellation, an unavailable KDE
Connect package, and overlapping taps leave pending states and acknowledgments
untouched. The transient request and retry flag survive activity recreation.

This is an explicit foreground readiness gate, not proof of delivery. The
existing distinction between transfer request, durable peer import, and
ReasonNotes acknowledgment remains binding. Fedora retains its machine-readable
KDE Connect reachability check. No carrier format, note/conflict/deletion logic,
or global KDE Connect configuration changed.

Verification used the project JDK 17 and Android SDK. The full Android JVM run
passed 212 tests with zero failures, errors, or skips, Android instrumentation
test sources compiled, and `assembleDebug` succeeded. The resulting offline APK
is `android-probe/app/build/outputs/apk/debug/app-debug.apk`, SHA-256
`a74f83dc478fc10742d3b9e4cf192fe34c126854b9aebf3440ce7bdbbddfeb83`.
It was not installed or launched. Actual launch/return/dialog behavior and the
pending physical acknowledgment retry remain subject to separately authorized
device verification.

## Simplified synchronization v2 — Android Task 4 complete (2026-10-25)

Android now sends and receives current `state-v2` and `ack-v2` records through
the existing canonical JSON bundle and KDE Connect share path. Import persists
state before queuing its exact acknowledgment, duplicate and reordered carriers
are idempotent, and accepted acknowledgments clear only the matching pending
change. Pending current state remains retryable without historical attempt
exhaustion, one conflicted note does not block independent notes, and unresolved
conflicts are named for explicit user choice rather than silently transmitted.

The visible Sync result is scoped to the current operation and uses four plain
states: synchronized, waiting for computer confirmation, transfer failed, or a
named note requiring conflict choice. New v2 outgoing carriers are exposed by
the existing non-exported FileProvider. The active application no longer starts
the legacy foreground purge/repository worker; deletion also erases drafts and
late draft writes cannot resurrect deleted content. The v2 receiver reuses the
validated processed-file index so an upgraded app does not rescan roughly 400
historical exchange files on its first ordinary open. Legacy v1 revisions remain
read-compatible, but v1 ledger/receipt history is not used to determine v2
operation status.

Fresh clean verification passed 199/199 Android JVM tests in 39 suites with no
failures, errors, or skips, followed by a successful `assembleDebug`. The APK is
`android-probe/app/build/outputs/apk/debug/app-debug.apk`, SHA-256
`eb0da72c4c6f281b4b2d3e4377f5b735cff3560330f5105956d7dd332a8519dd`, package
`org.reasonnotes.probe`, version 1 / 0.1, min SDK 26, target SDK 35, signed with
the established certificate SHA-256
`d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147`.

No APK was installed or launched, no Android or personal data was migrated, and
no synchronization occurred. Product completion still requires Fedora Tasks 5
through 7, cross-platform Task 8, and separately authorized physical acceptance.

## Simplified synchronization v2 — Android Task 3 current-state application path (2026-10-24)

The Android Home, Editor, conflict chooser, and bulk-deletion service path now
use the Task 2 current-state store instead of reconstructing the immutable
legacy revision graph for ordinary note operations. A guarded application
opener performs the already-tested one-time staged migration and then opens
only `reasonnotes-v2`. Repeated edits replace one current record and one pending
state, conflicts retain both complete versions for explicit choice, and local
deletion immediately erases note content while retaining only a content-free
deletion fence and pending deletion operation. The editor no longer runs a
post-open legacy-history reconciliation scan.

The legacy `NoteService` constructor remains available only as a compatibility
adapter for the not-yet-converted v1 transport and its tests. The old incoming
receiver is deliberately disconnected after v2 activation; Task 4 must replace
it with the v2 carrier/acknowledgment path before any APK can be built or
deployed for product testing.

Focused direct-JDK verification passes 34 tests covering current-state CRUD,
restart, migration, two-version conflicts, selected resolution, independent
bulk deletion, permanent deletion, stale-upsert rejection, legacy service
compatibility, and listing seven current notes without touching 400 synthetic
legacy files. Android Gradle compilation and APK assembly were not run because
the previously documented SDK and project Gradle cache remain unavailable.
No application was launched, no APK was built or installed, and no personal
data was migrated or synchronized. Product completion still requires Tasks 4
through 8 and separately authorized physical acceptance.

## Selectable pending deletions — 2026-10-24

Physical testing confirmed Fedora creation/edit round-trip, durable receipts,
explicit two-version conflict choice and convergence, and Fedora-to-Android
deletion. It also exposed a safety gap for the reverse deletion direction:
Android hid deleted notes from the selected-note picker, making Sync all the
only way to send a tombstone from a phone containing personal notes.

The picker now retains a deleted note only while its tombstone is pending,
requested, or needs attention, labels it `Deleted`, and permits selecting it
independently. Once confirmed, the deleted entry disappears. Selected Sync now
includes the selected tombstone and its required ancestry without including
unrelated notes. This preserves ordinary JSON v1 transport and receipts while
providing a safe, understandable recovery path.

The new picker/planner regressions failed before the change and pass after it.
The complete Android host suite passes 161 tests and `assembleDebug` succeeds;
APK SHA-256 is
`b985ee2d119d15de1e471f4bd63105157289960754611b9fa87e0a8b215fa3c3`.
Physical installation and Android-to-Fedora deletion verification remain
pending. Existing phone data and ledgers were not reset or cleared.

## Four-requirement synchronization repair — 2026-10-23

The product requirements are: notes remain editable on Android and Fedora;
pressing Sync sends eligible changes; concurrent edits show both complete
versions and require an explicit choice; and deletion on either device is sent
to the other device without resurrection.

Android now sends ordinary deletions as immutable JSON v1 tombstone revisions
through the same Sync action as edits. Sync all includes tombstones even though
deleted notes are intentionally absent from the note picker; Sync selected
does not silently include deleted notes outside its visible scope. A concurrent
edit and deletion remain two heads and therefore enter the existing explicit
conflict chooser. Legacy permanent-purge fences remain readable for backward
compatibility, but new user deletions no longer depend on the separate
foreground-purge channel.

The ordinary-sync ancestry repair remains in place: a pending descendant can
carry its exhausted required ancestor, so an old failed attempt does not block
the newer safe chain or unrelated notes. Ledger evidence is preserved and
failures are not relabeled as success.

The complete Android host suite passes 161 tests and `assembleDebug` succeeds.
The resulting debug APK SHA-256 is
`a5a03c7437ba14c3a0b5d0e774b1c9b7e64bca9dadd137fe4cbb420b1a0da444`.
No APK was installed and no live synchronization or personal-note operation
was performed. Physical create/edit round-trip, conflict choice, deletion in
both directions, and restart persistence remain required acceptance.

## Ordinary Sync ancestry recovery — 2026-10-23

Original product requirements are: editable notes on Fedora and Android;
user-triggered synchronization of changes; explicit two-version conflict
choice; and deletion convergence without resurrection.

Android ordinary Sync previously refused a new pending revision when one of
its required local ancestors had exhausted three unconfirmed transfer
attempts. The result was no carrier and the generic “Some notes need
attention” message. `SyncPlanner` now treats a newer pending descendant as a
safe, user-initiated recovery reason: it includes the exhausted local ancestor
and pending descendant in one JSON v1 carrier. It does not reset ledger
evidence, retry an exhausted revision by itself, or bypass durable receipts.
Independent pending notes remain in the same plan. A rejected KDE Connect
request leaves the ancestor at `needs-attention` and the child `pending`, so a
later ordinary Sync remains possible.

Focused Android planner/coordinator, JSON transport, conflict, deletion and UI
contract verification passed 57 tests; `assembleDebug` passed. The offline APK
SHA-256 is `ad0b8a758719447867691c3d9df18b81a665f292c5bfebe208b53b7860161fd2`.
Fedora conflict/deletion/restart/confirmed-sync evidence was refreshed with
33 passing focused tests; no Fedora source changed.

This section records the earlier ancestry-only checkpoint. The later
four-requirement repair above supersedes its former deletion limitation.

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

The repaired sender APK was subsequently installed in place with its hash,
signer, existing receipt attempt, immutable revision, and SAF grant verified.
Before retry, inspection found that the old retry path would include 14
historical receipts. A focused follow-up now limits selected-note retry to
receipts for the selected note while preserving ordinary unconditional pending
receipts and Retry all semantics. The new regression failed before the change;
the complete host suite passes 159 tests and the debug APK builds. The staged
targeted-retry APK SHA-256 is
`3a5fcbf5fff7ec87d58dca03b82bd5d7e8c524ca3e3c202e94ab60a253603622`.
It has not been installed; the physical one-receipt retry remains pending.

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

## Forge physical synchronization checkpoint — 2026-10-24

Original acceptance remains: save/edit on Fedora and Android, explicit
user-triggered transfer, two-version conflict choice, and deletion propagation
in both directions with durable peer confirmation. Physical testing used only
disposable notes and the isolated Fedora repository at
`/home/reason/Temporary/ReasonNotes-Physical-Acceptance-d312682-6527405/`.

Verified on the connected POCO with installed APK SHA-256
`b985ee2d119d15de1e471f4bd63105157289960754611b9fa87e0a8b215fa3c3`:

- Fedora create -> Android import -> Android receipt -> Fedora confirmation.
- Android edit -> Fedora import -> Fedora receipts -> Android confirmation.
- Independent edits produced two heads; Reason selected the Fedora version and
  the resolution revision converged on both devices.
- Fedora deletion propagated to Android and was durably confirmed by receipt.
- Android deletion revision
  `76826346641842125b663079d9b2a58b86087e733612bcaf789b06a203642092`
  propagated to Fedora and removed the disposable note there.

Two blocking defects were discovered and preserved without further live retry:

1. Android stored Fedora's receipt for the Android-origin deletion, but the
   tombstone had no corresponding transfer-requested delivery-ledger row, so
   strict receipt validation could not confirm it. Runtime evidence shows
   MainActivity and SyncActivity can perform overlapping exchange scans while
   separate ledger instances use read/replace writes; this can lose a newly
   recorded row.
2. Android rescans the complete shared exchange directory on foreground/sync.
   Measured scans processed 396-398 files and took 38.5-58.3 seconds each.
   A read-only filename count later found 401 files: 346 standalone receipts,
   41 Markdown/revision files, 11 JSON v1 bundles, and 3 other files. The owner
   reports severe UI lag, including NEW NOTE.

No source repair is included in this checkpoint. Proposed next implementation,
pending design approval: serialize/coalesce process-wide scans, make ledger
updates transactional across instances, and add a durable processed-carrier
index so unchanged content-addressed files are not reread. A new APK must not
be installed without separate approval of its exact hash. Personal notes were
not transmitted, removed, or modified by the acceptance fixtures.

## Forge Android scan correctness/performance repair — 2026-10-24

The physically observed ledger race and repeated full-content scans are repaired
in source. Delivery-ledger read/modify/write operations used by discovery,
transfer requests, and receipt confirmation now share a process-wide transaction
boundary across Activity-owned instances. Incoming SAF scans also share one fair
process-wide gate, preventing MainActivity and SyncActivity from processing and
rewriting evidence concurrently.

A private durable processed-document index records only successfully read and
validated exchange files with reliable provider modification time and size.
Subsequent scans list the directory but skip unchanged validated payloads; new
or metadata-changed files are read and cryptographically validated normally.
Malformed/rejected files and files with unreliable metadata are not skipped.
Shared KDE Connect files are never deleted or modified.

TDD evidence: the concurrent multi-instance ledger test failed before locking
and passed afterward; the missing scan gate and processed-index types failed
before implementation. Focused index/exchange/gate tests pass. Full Android JVM
suite: 165 tests, zero failures/errors. `assembleDebug` passes. Debug APK is
2,711,013 bytes with SHA-256
`49a87c72fb272e874ac3ef49162895bdaf8876bc20571ae798299085775f25df`.
The APK is not installed; real-device first-index and warm-scan timing, NEW NOTE
responsiveness, and final deletion-receipt confirmation remain physical gates.

## Forge Android NEW NOTE latency repair — 2026-10-24

Physical review of the installed scan-index candidate showed that NEW NOTE still
visibly lagged. Device evidence separated the stages: EditorActivity itself was
displayed in 99 ms, while launch was delayed during a 3.46-second foreground
incoming check. Source tracing found two additional critical-path costs: the
home screen queued a full generated-view reconciliation on the same executor as
NEW NOTE, and first-revision creation loaded and reconciled all unrelated note
history.

The home screen no longer queues generated-view maintenance ahead of user
actions. A new guarded initial-revision path durably fsyncs the immutable first
revision without loading unrelated history or rebuilding generated views.
Normal edits retain full expected-head and incomplete-ancestry validation;
EditorActivity still performs deferred generated-view reconciliation only after
the writing surface is interactive. A regression verifies that the first
revision is durable immediately and that explicit reconciliation reconstructs
the generated view.

Focused repository/service/editor tests pass, followed by the full Android JVM
suite and `assembleDebug`. Candidate APK SHA-256:
`6e2f4e72b7f4edaa97409150b35e41dcadc152482bbdc0f14ddbf0208cd2986e`
(2,711,214 bytes). The candidate is not installed; actual NEW NOTE latency on
the POCO remains the physical acceptance gate.

## Simplified synchronization v2 checkpoint — 2026-10-24

Reason approved replacing history-wide normal operation with the bounded v2
design in `docs/SIMPLIFIED_SYNC_V2_DESIGN_2026-10-24.md`; the executable plan
is `docs/superpowers/plans/2026-10-24-simplified-sync-v2.md`. The proven JSON
bundle/KDE Connect path remains, while normal state becomes one current record
per note, one coalesced pending change, temporary edit/edit conflict candidates,
exact acknowledgments, and content-free deletion fences. Deletion permanently
erases recoverable note content and wins over edits.

Task 1 is complete in both repositories. Java and Python canonical v2 state and
acknowledgment records share byte-identical fixtures and reject tampering;
deletion records contain no title/body. Android full JVM tests passed. Fedora
full suite passed 296/296 when run outside the socket-restricted sandbox.
Verified Task 1 commits were Android `ee50f1a470924d13d1c3b4f5bbb596d1f726c10f`
and Fedora `7de5e4678c671dcd6d6a8dc813b46d000f530ec7`, each mirrored to `handoff`
and `github` before Task 2 began.

Task 2 is complete at source and focused-test level. `CurrentNoteStore` now
persists one live state and one coalesced pending state per note, stores exactly
two complete edit-conflict candidates, creates exact outgoing acknowledgments,
uses content-free durable deletion fences, rejects stale live carriers after
deletion, and recovers a committed current write whose pending rename was
interrupted. All records use fsynced temporary files and atomic replacement.

`LegacyRepositoryMigrator` validates every legacy history file and ancestry,
refuses malformed, incomplete, or more-than-two-head notes, rebuilds partial
staging, reopens and compares the staged v2 store, atomically selects it, and
only then erases the old synthetic root. Focused direct-JDK verification passed
10/10 store and migration tests. The tests use only JUnit `TemporaryFolder`
roots and contain no Android application-data references. The documented
Android SDK and project Gradle cache were absent, so Gradle did not reach
compilation; no APK build is claimed for Task 2.

PRODUCT INCOMPLETE. No v2 application candidate was built, installed, launched,
or synchronized, and no personal-data migration was run. The currently
installed Android application and all personal data remain on the previously
accepted checkpoint. Task 3 application/service integration is the next plan
task, not part of this checkpoint.

## Simplified synchronization v2 Task 8 partial checkpoint — 2026-10-25

The original product goal remains save/edit on both devices, explicit Sync
transfer, complete two-version conflict choice, and permanent bidirectional
deletion. Task 8 is interrupted at a useful cross-platform fixture checkpoint.

Android now contains a deterministic eight-carrier fixture transcript shared
with Fedora. The compiled Java implementation generated its four Android-owned
canonical JSON carriers and verified all eight byte-for-byte. Its focused
cross-platform test also imports Fedora's edit, conflict resolution,
acknowledgment, and deletion carriers through `CurrentTransport`; conflict
choice converges, exact acknowledgment clears pending state, deletion in both
directions removes active content, and deleted text is absent from the store.
Focused Gradle verification passed 2/2 using the retained project JDK 17 and
Android SDK supplied command-locally. The host's new Java `25.0.4.1` is not
parseable by this project's Kotlin tooling; no system configuration changed.

Task 8 is NOT complete. The full Android suite and APK build have not been run,
the offline acceptance document and project status on both sides still require
finalization, no candidate artifact exists yet, and physical acceptance remains
separately gated. No APK was installed, no application was launched, no
personal data was migrated, and no KDE Connect transfer occurred.

## Simplified synchronization v2 Task 8 complete offline — 2026-10-25

The Android/Fedora eight-carrier fixture transcript is complete and
byte-identical across Java and Python. Offline coverage demonstrates create,
independent edits, two-version conflict choice, chosen-version convergence,
permanent deletion from either platform, stale-carrier non-resurrection,
duplicates, restart persistence, unreachable retry, independent-note progress,
and exact acknowledgment convergence. Deleted title/body text is absent from
stores and deletion carriers.

Final Android verification passed 201/201 tests across 40 suites with no
failures, errors, or skips; `assembleDebug` succeeded. Candidate APK:
`/home/reason/Temporary/ReasonNotes-Sync-v2-Candidates/android/ReasonNotes-Android-Sync-v2-100b3e0.apk`,
SHA-256 `eb0da72c4c6f281b4b2d3e4377f5b735cff3560330f5105956d7dd332a8519dd`.
It retains package `org.reasonnotes.probe`, the accepted signing certificate,
and packaged launcher icon. Runtime source is checkpoint `100b3e0ca8d0c3c9371a6e24a5fde33a3931753b`.

Task 8's offline milestone is complete; product acceptance is not. The APK was
not installed or launched. First launch performs a validated but one-way legacy
history migration, so fresh recovery evidence and separate authorization are
required before touching the POCO. Physical transfer, durable acknowledgments,
conflict UI, deletion convergence, retry, restart persistence, and performance
remain unverified. See
`docs/SIMPLIFIED_SYNC_V2_OFFLINE_ACCEPTANCE_2026-10-24.md`.

## Direct exchange access partial checkpoint — 2026-10-25

Physical Sync v2 testing proved KDE Connect delivery of the single disposable
`sync-v2-roundtrip` carrier, but Android's SAF receiver did not enumerate it.
The exact 626-byte carrier is present in `/storage/emulated/0/ReasonNotes
Exchange` and MediaStore exposes it as row `1000014125`; Android ran incoming
scans at 12:53 and 13:04 without importing it or queuing an acknowledgment.
The persisted SAF grant is valid, while Google DocumentsUI v17 refuses every
replacement folder on this POCO. No personal note was transmitted by this
test and Sync all was not pressed.

Reason approved replacing SAF as the normal receive path with Android All
files access, constrained in application code to the fixed `ReasonNotes
Exchange` directory, plus removal of the obsolete Android foreground-deletion
pairing controls. The approved design and executable plan are:

- `docs/superpowers/specs/2026-10-25-direct-exchange-access-design.md`
- `docs/superpowers/plans/2026-10-25-direct-exchange-access.md`

Task 1 is partially complete. `SharedExchangeDirectory` directly scans only an
explicit directory, reuses canonical carrier validation/current-state apply,
queues exact acknowledgments, reports malformed carriers, and durably skips
successfully processed files. `IncomingReceive` selects it only when Android
reports All files access; otherwise the existing SAF path remains. Focused TDD
verification passed both scanner tests after the expected missing-class RED.

PRODUCT INCOMPLETE. Manifest permission, user-facing special-access Settings
action, obsolete pairing-control removal, broader verification, APK build and
artifact identity remain unimplemented. No new APK was built or installed and
no phone data, grants, KDE Connect settings, notes, or ledgers were modified by
the source work. Resume with Task 2's Settings/manifest RED tests; do not repeat
the completed physical diagnosis or Task 1 focused test without contrary
evidence.

## Direct exchange access implementation complete — 2026-10-25

The approved direct receive path is implemented. On Android 11 and newer,
`IncomingReceive` uses All files access to scan only the fixed
`/storage/emulated/0/ReasonNotes Exchange` directory; without that special
access it safely retains the existing SAF compatibility path. The scanner
reuses canonical bundle validation and current-state application, queues exact
acknowledgments, reports malformed carriers, and persists successful file
fingerprints to prevent replay work.

Android Settings now exposes a single `Storage access` action/status that opens
the application-specific All files access screen. The obsolete foreground LAN
deletion pairing controls are removed from the UI; normal JSON synchronization
remains responsible for deletion propagation. The fixed KDE Connect computer
target and all unrelated settings remain.

TDD evidence:

- `SharedExchangeDirectoryTest`: expected missing-class RED, then 2/2 PASS for
  canonical import/ack, unrelated-file handling, durable replay skipping and
  malformed-carrier rejection.
- `SettingsActivityContractTest`: expected missing API RED, then PASS for the
  new storage action/status, manifest permission and absence of legacy pairing.
- Final Android JVM suite: 205 tests PASS.
- `assembleDebug`: PASS.

Candidate APK:

- `/home/reason/Temporary/ReasonNotes-Sync-v2-Candidates/android/ReasonNotes-Android-Sync-v2-direct-exchange-6d412f4.apk`
- SHA-256 `6d412f4b0c6feef5c7186c9f68e6acbaa6de5f4383d0d07cc5ffac49f8dd77f5`
- 2,712,633 bytes; package `org.reasonnotes.probe`, version `1` / `0.1`
- APK Signature Scheme v2; certificate SHA-256
  `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147`
- accepted launcher resource packaged; `MANAGE_EXTERNAL_STORAGE` packaged.

PRODUCT INCOMPLETE. This exact APK has not been installed and All files access
has not been enabled. The already-delivered disposable carrier remains on the
phone, unimported and unacknowledged. Remaining acceptance requires explicit
authorization to install this exact hash, enable its special access once, then
verify import, durable acknowledgment return, and the rest of physical Sync v2
acceptance. No personal notes, phone data, KDE Connect configuration, desktop
installation or production files were changed by this implementation.

## Bounded autosave ancestry repair — 2026-10-25

Sequential pending autosaves now retain their original parent set, so A -> B
-> C sends bounded C as a direct descendant of A. Valid complete states with
unorderable ancestry become explicit conflicts instead of `needs-attention`;
superseding imports clear obsolete pending state. Genuine sibling conflicts
remain protected. TDD reproduced the physical failure before implementation;
all 207 Android JVM tests pass.

Candidate:
`/home/reason/Temporary/ReasonNotes-Sync-v2-Coalesced-Candidates/android/ReasonNotes-Android-coalesced-8f3d4f1.apk`, SHA-256
`8f3d4f1513139b4ccbfe61d3e91baa0c8ed804b7ffe9fb9047d443e1e034fb96`.
It has not been installed. Physical confirmation remains pending.

## Physical conflict recovery checkpoint — 2026-10-25

APK `8f3d4f15…` was installed in place and retained All files access. The old
unknown-parent carrier became an explicit two-version conflict on Fedora.
Reason selected the Android text. Fedora sent resolution change
`7d3836fc2c77f0e2ca0aff6c5c0a4dfa7a61cce935d8c2c07df0af57671016c7`;
Android durably imported it and queued its exact 348-byte acknowledgment.
Both current states contain the selected Android text. The acknowledgment has
not been returned, so Fedora correctly remains unconfirmed. No retry was made.
