# ReasonNotes Milestone 1 KDE Connect Feasibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and physically test the smallest safe revision-exchange prototype that can decide whether KDE Connect provides usable bidirectional synchronization between Fedora Linux and the POCO X6 5G.

**Architecture:** A dependency-free Python package owns the canonical Markdown revision format, immutable history graph, deterministic conflict resolution, bounded delivery ledger, and Linux adapter. A minimal native Android probe independently implements the same wire format and graph rules, scans one persisted Storage Access Framework directory, and invokes KDE Connect's exported share activity from a user-visible `Sync now` action. Immutable history is authoritative; generated indexes and working views are rebuilt from it after interruption.

**Tech Stack:** Python 3.14 standard library and `unittest`; `kdeconnect-cli` 26.08.1 plus `gdbus`; native Android Java, Android Gradle Plugin 8.7.3, Gradle 8.9, JDK 17, compile/target SDK 35, minimum SDK 26; AndroidX Core and DocumentFile only.

**Spec:** `docs/superpowers/specs/2026-10-12-kde-connect-feasibility-design.md`

## Global Constraints

- Work only on `forge/kdeconnect-feasibility` in `/home/reason/Codex-Projects/ReasonNotes/.worktrees/forge-kdeconnect-feasibility`.
- Notes and revision envelopes remain human-readable Markdown; no database or cloud service.
- Never select a conflict winner by timestamp, overwrite a competing head, or insert conflict markers into note content.
- A resolution revision uses the chosen content and lists every observed maximal head as a sorted, unique parent set.
- Immutable history is written before replaceable state/index/working-view files and is never deleted by synchronization.
- Normal sync requests each pending revision at most once. Only explicit retry commands resend unconfirmed revisions; three ordinary requests move an entry to `needs-attention`.
- `transfer-requested`, `kdeconnect-transfer-complete`, and `reasonnotes-imported` are separate event kinds and must not be inferred from one another.
- Android outgoing transfer is user initiated. Do not add background services, accounts, Google services, telemetry, or a full notes UI.
- Do not mutate KDE Connect configuration automatically or use unrelated user files in tests.
- Physical Android behavior remains pending until observed on device ID `52548cf9710c43cab2fa73ed4671aea9`.

## Review Focus

- An envelope with unknown parents must remain pending and must not alter heads; covered by Task 2 out-of-order graph tests.
- A stale resolution that omits a concurrent head must leave a conflict; covered by Task 3 stale-resolution tests.
- A crash between history, index, and working-view writes must reconcile from history without losing a note; covered by Task 2 recovery tests.
- A successful IPC/activity launch must record only `transfer-requested`, never completion or import; covered by Tasks 4, 5, and 9.
- Android direct targeting, persisted tree access, URI grants, and OEM lifecycle behavior cannot be proved by host tests; covered by Task 10's physical acceptance matrix.

---

### Task 1: Canonical Markdown Revision Envelope

**Files:**
- Create: `pyproject.toml`
- Create: `src/reasonnotes_sync/__init__.py`
- Create: `src/reasonnotes_sync/envelope.py`
- Create: `tests/__init__.py`
- Create: `tests/test_envelope.py`

**Interfaces:**
- Produces: `EnvelopeError(ValueError)`.
- Produces: immutable `Revision(note_id: UUID, revision_id: str, parents: tuple[str, ...], origin_device: str, modified_utc: datetime, deleted: bool, body_sha256: str, body: str)`.
- Produces: `Revision.create(note_id, parents, origin_device, modified_utc, deleted, body) -> Revision`.
- Produces: `Revision.parse(data: bytes) -> Revision`, `Revision.serialize() -> bytes`, and `Revision.transport_filename() -> str`.
- Canonical format marker: `reasonnotes-format: 1`; hashes are lowercase 64-character hexadecimal SHA-256 values; parents are unique and lexically sorted before hashing and serialization.

- [ ] **Step 1: Add packaging metadata and failing envelope tests**

Create dependency-free `pyproject.toml` metadata and tests named:

- `test_round_trip_preserves_unicode_markdown_and_sorted_parents`
- `test_revision_id_covers_metadata_parent_set_and_body`
- `test_rejects_truncated_body_hash_mismatch`
- `test_rejects_malformed_uuid_hash_timestamp_boolean_and_device_id`
- `test_transport_filename_is_not_trusted_during_parse`
- `test_tombstone_requires_empty_body`

The production change that makes them pass is a strict canonical parser/serializer whose revision hash excludes only the `revision-id` field itself.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_envelope -v`

Expected: FAIL because `reasonnotes_sync.envelope` and its API do not exist.

- [ ] **Step 3: Implement the minimal envelope model**

Implement the interfaces above using only `dataclasses`, `datetime`, `hashlib`, `re`, and `uuid`. Reject unknown or repeated metadata keys, non-UTF-8 input, carriage-return ambiguity, duplicate parents, and bodies whose bytes do not match `body-sha256`.

- [ ] **Step 4: Run focused and full tests and verify GREEN**

Run: `PYTHONPATH=src python3 -m unittest tests.test_envelope -v`

Expected: all Task 1 tests PASS.

Run: `PYTHONPATH=src python3 -m unittest discover -s tests -v`

Expected: full suite PASS with no warnings or tracebacks.

- [ ] **Step 5: Commit Task 1**

```bash
git add pyproject.toml src/reasonnotes_sync tests
git commit -m "feat: define canonical note revision envelopes"
```

### Task 2: Durable Revision Graph, Import, and Reconciliation

**Files:**
- Create: `src/reasonnotes_sync/graph.py`
- Create: `src/reasonnotes_sync/repository.py`
- Create: `tests/test_graph.py`
- Create: `tests/test_repository.py`

**Interfaces:**
- Consumes: Task 1 `Revision` and `EnvelopeError`.
- Produces: `GraphStatus(head_ids: tuple[str, ...], pending_ids: tuple[str, ...], conflicted: bool)`.
- Produces: `RevisionGraph(revisions: Iterable[Revision])`, `status(note_id: UUID) -> GraphStatus`, `heads(note_id) -> tuple[Revision, ...]`, and `is_ancestor(ancestor_id, descendant_id) -> bool`.
- Produces: `ImportOutcome(kind: Literal["imported", "duplicate", "quarantined"], revision_id: str | None, detail: str)`.
- Produces: `Repository.open(root: Path) -> Repository`, `import_bytes(data, source_name) -> ImportOutcome`, `create_revision(note_id, body, origin_device, modified_utc, deleted=False) -> Revision`, and `reconcile() -> ReconcileReport`.
- `create_revision` accepts a new note or a note with one current head; it fails with `ConflictRequired` when several heads exist so ordinary edits cannot resolve conflicts implicitly.
- Repository layout: `history/<note-id>/<revision-id>.md`, `state/index.json`, `notes/<note-id>.md`, `conflicts/<note-id>.json`, and `quarantine/`.

- [ ] **Step 1: Write failing graph tests**

Add tests for duplicate import, linear fast-forward, two maximal heads, unknown-parent pending state, an out-of-order parent activating its child, multi-parent resolution collapsing its acknowledged heads, and a late ancestor remaining non-head.

- [ ] **Step 2: Run graph tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_graph -v`

Expected: FAIL because `RevisionGraph` does not exist.

- [ ] **Step 3: Implement graph derivation**

Treat only revisions with recursively complete ancestry as eligible for maximal-head calculation. Use graph ancestry, never timestamps, to derive heads. Keep pending revisions addressable without allowing them to materialize a note.

- [ ] **Step 4: Run graph tests and verify GREEN**

Run: `PYTHONPATH=src python3 -m unittest tests.test_graph -v`

Expected: all graph tests PASS.

- [ ] **Step 5: Write failing durability and reconciliation tests**

Add tests named:

- `test_import_writes_history_before_index_and_working_view`
- `test_reconcile_repairs_stale_index_after_history_only_interruption`
- `test_reconcile_repairs_missing_working_view_from_history`
- `test_hash_mismatch_is_quarantined_without_state_change`
- `test_duplicate_under_renamed_transport_file_is_idempotent`
- `test_tombstone_removes_working_view_but_preserves_history`
- `test_conflict_preserves_previous_working_view_and_all_heads`
- `test_ordinary_edit_fails_closed_while_note_is_conflicted`

Simulate interruptions by placing valid history with a stale/missing generated index or view, then reopening and reconciling.

- [ ] **Step 6: Run repository tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_repository -v`

Expected: FAIL because `Repository` does not exist.

- [ ] **Step 7: Implement durable storage and reconciliation**

Write new history files with exclusive creation, flush and `os.fsync`, then atomically replace JSON indexes and working views through same-directory temporary files. Sync containing directories after rename. Derive every generated file from immutable history during `reconcile()`.

- [ ] **Step 8: Run focused and full tests and verify GREEN**

Run: `PYTHONPATH=src python3 -m unittest tests.test_repository tests.test_graph -v`

Run: `PYTHONPATH=src python3 -m unittest discover -s tests -v`

Expected: all tests PASS.

- [ ] **Step 9: Commit Task 2**

```bash
git add src/reasonnotes_sync/graph.py src/reasonnotes_sync/repository.py tests/test_graph.py tests/test_repository.py
git commit -m "feat: add durable revision graph storage"
```

### Task 3: Explicit Conflict Selection and Convergence

**Files:**
- Create: `src/reasonnotes_sync/conflicts.py`
- Create: `src/reasonnotes_sync/cli.py`
- Create: `src/reasonnotes_sync/__main__.py`
- Create: `tests/test_conflicts.py`
- Create: `tests/test_cli.py`

**Interfaces:**
- Consumes: Task 2 `Repository` and `RevisionGraph`.
- Produces: `ConflictCandidate(revision_id: str, origin_device: str, deleted: bool, preview: str)`.
- Produces: `list_conflict(repository, note_id) -> tuple[ConflictCandidate, ...]`.
- Produces: `resolve_conflict(repository, note_id, choice: Literal["keep-linux", "keep-android", "skip"], resolver_device, modified_utc) -> Revision | None`.
- `keep-linux` and `keep-android` require exactly one current head from that origin label; ambiguous device labels fail closed and the CLI offers `--revision-id` for an exact selection.
- A non-skip resolution copies the chosen head's body/deletion state and uses every current maximal head as parents.

- [ ] **Step 1: Write failing conflict-resolution tests**

Cover readable device-labeled previews; keep-Linux; keep-Android; skip; edit-versus-delete selection in both directions; ambiguous same-device heads; stale resolution plus a concurrent third head; and preservation of superseded history.

- [ ] **Step 2: Run conflict tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_conflicts -v`

Expected: FAIL because conflict selection is missing.

- [ ] **Step 3: Implement deterministic resolution**

`skip` performs no writes. A keep choice creates and stores one new local revision and rematerializes the now-converged working view. Task 4 attaches delivery registration to that repository operation. Do not merge text or modify either selected or superseded history artifact.

- [ ] **Step 4: Run conflict tests and verify GREEN**

Run: `PYTHONPATH=src python3 -m unittest tests.test_conflicts -v`

Expected: all conflict tests PASS.

- [ ] **Step 5: Write failing CLI tests**

Test `status`, `conflicts`, and `resolve --choice keep-linux|keep-android|skip`, including exact device labels, content previews, selected revision ID, parent IDs, and unchanged state for skip.

- [ ] **Step 6: Implement the minimal CLI commands**

Use `argparse` and support `--root`, `--note-id`, `--device-id`, and deterministic `--modified-utc` for fixtures. Keep output line-oriented and machine-checkable; no interactive TUI.

- [ ] **Step 7: Run full tests and commit Task 3**

Run: `PYTHONPATH=src python3 -m unittest discover -s tests -v`

Expected: full suite PASS.

```bash
git add src/reasonnotes_sync/conflicts.py src/reasonnotes_sync/cli.py src/reasonnotes_sync/__main__.py tests/test_conflicts.py tests/test_cli.py
git commit -m "feat: add explicit conflict resolution"
```

### Task 4: Bounded Delivery Ledger and Event Evidence

**Files:**
- Create: `src/reasonnotes_sync/delivery.py`
- Create: `tests/test_delivery.py`
- Modify: `src/reasonnotes_sync/repository.py`
- Modify: `src/reasonnotes_sync/cli.py`

**Interfaces:**
- Produces: `DeliveryState` values `pending`, `transfer-requested`, and `needs-attention`.
- Produces: `EventKind` values `transfer-requested`, `kdeconnect-transfer-complete`, and `reasonnotes-imported`.
- Produces: `DeliveryLedger(root: Path, max_requests: int = 3)`.
- Produces: `register_local(revision_id, target_device)`, `eligible(target_device, retry_unconfirmed=False, force_ids=())`, `record_requested(revision_id, target_device, observed_utc)`, and `record_event(kind, revision_id, observer_device, observed_utc, detail)`.
- Persistence: atomic `state/delivery.json` plus append-only, flushed `state/events.jsonl`; history remains the recoverable content source.

- [ ] **Step 1: Write failing ledger tests**

Test one default request, no implicit resend, one request per explicit retry run, transition to `needs-attention` after the third request, explicit targeted force-retry, unavailable target leaving pending, local creation registering pending, imported remote revisions not being reflected back automatically, and distinct event kinds never implying one another.

- [ ] **Step 2: Run ledger tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_delivery -v`

Expected: FAIL because the ledger is missing.

- [ ] **Step 3: Implement ledger persistence and repository hooks**

Register locally created and locally resolved revisions for the configured other device. Do not put an imported remote revision into the two-device outbox. Keep attempts and last-request timestamps visible in CLI status.

- [ ] **Step 4: Add CLI retry/status coverage**

Test `outbox`, `retry-unconfirmed`, and `force-retry --revision-id`; verify output distinguishes requested, completion-observed, and imported events.

- [ ] **Step 5: Run focused and full tests and commit Task 4**

Run: `PYTHONPATH=src python3 -m unittest tests.test_delivery tests.test_cli -v`

Run: `PYTHONPATH=src python3 -m unittest discover -s tests -v`

Expected: all tests PASS.

```bash
git add src/reasonnotes_sync/delivery.py src/reasonnotes_sync/repository.py src/reasonnotes_sync/cli.py tests/test_delivery.py tests/test_cli.py
git commit -m "feat: bound unconfirmed revision retries"
```

### Task 5: Linux KDE Connect Adapter and Scan-Then-Send Sync

**Files:**
- Create: `src/reasonnotes_sync/kdeconnect_linux.py`
- Create: `src/reasonnotes_sync/sync.py`
- Create: `tests/test_kdeconnect_linux.py`
- Create: `tests/test_sync.py`
- Modify: `src/reasonnotes_sync/cli.py`

**Interfaces:**
- Produces: `KdeConnectLinux(device_id: str, command_runner)`.
- Produces: `reachable() -> bool`, `request_transfer(path: Path) -> TransferRequest`, and `received_paths(lines: Iterable[str]) -> Iterator[Path]`.
- Produces: `scan_inbox(repository, inbox: Path, local_device: str) -> ScanReport`.
- Produces: `sync_linux(repository, inbox, adapter, retry_unconfirmed=False, force_ids=()) -> SyncReport` with imports, conflicts, pending, requested, and errors as separate fields.
- `kdeconnect-cli` exit zero means request accepted only. A parsed receiving-side `shareReceived(file://...)` signal can record KDE Connect completion before attempting ReasonNotes import.

- [ ] **Step 1: Write failing adapter tests**

Use a small fake command runner to test exact environment/arguments, the known device ID, unavailable-device failure, path handling with spaces, request acceptance versus completion, D-Bus URL decoding, rejection of non-file URLs, and filtering unrelated Downloads files.

- [ ] **Step 2: Run adapter tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_kdeconnect_linux -v`

Expected: FAIL because the Linux adapter is missing.

- [ ] **Step 3: Implement the adapter**

Use `kdeconnect-cli --list-available --id-only` for reachability and `kdeconnect-cli --device <id> --share <path>` for requests. Supply `XDG_RUNTIME_DIR=/run/user/1000` and `DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus` through an explicit environment builder, not global mutation. Parse only the installed share object's signal path.

- [ ] **Step 4: Write failing sync orchestration tests**

Cover scan-before-send ordering, no request record when unreachable, one request for each pending local revision, no default resend, explicit retry, import event after durable import only, conflict reporting, and listener recovery through a later one-shot scan.

- [ ] **Step 5: Implement `sync_linux` and CLI `scan`, `sync`, and `watch`**

The `watch` command consumes `gdbus monitor` lines, records receiving-side transport completion for valid file URLs, imports, and reports failures without deleting the received file.

- [ ] **Step 6: Run full tests and commit Task 5**

Run: `PYTHONPATH=src python3 -m unittest discover -s tests -v`

Expected: all tests PASS with no live transfer attempted by tests.

```bash
git add src/reasonnotes_sync/kdeconnect_linux.py src/reasonnotes_sync/sync.py src/reasonnotes_sync/cli.py tests/test_kdeconnect_linux.py tests/test_sync.py
git commit -m "feat: add Linux KDE Connect sync adapter"
```

### Task 6: Two-Device Convergence Fixtures

**Files:**
- Create: `tests/test_bidirectional_sync.py`
- Create: `tests/fixtures/README.md`
- Create: `scripts/run-two-device-demo.sh`
- Create: `docs/milestone1/AUTOMATED_EVIDENCE.md`

**Interfaces:**
- Consumes: Tasks 1-5 public APIs and CLI.
- Produces: a disposable two-directory demo under a caller-supplied temporary root; the script never uses `~/Downloads` or live KDE Connect.

- [ ] **Step 1: Write failing end-to-end scenarios**

Test:

- independent non-conflicting note creation followed by exchange and equal heads/bodies;
- same-note offline edits producing two device-labeled heads;
- keep-Linux and keep-Android each producing a multi-parent resolution that converges on both repositories;
- skip preserving the conflict;
- edit-versus-delete resolution both ways;
- a stale resolution plus a concurrent head remaining conflicted;
- duplicate, renamed, late-ancestor, and out-of-order delivery after resolution;
- history-only interruption followed by equal reconciled heads and working views;
- bounded retry state throughout the exchange.

- [ ] **Step 2: Run scenarios and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_bidirectional_sync -v`

Expected: at least one scenario FAILS until any missing orchestration behavior is implemented.

- [ ] **Step 3: Add only the missing orchestration behavior**

Do not add text merge, acknowledgement packets, background work, or extra transports. Keep all fixtures deterministic with fixed UUIDs and UTC timestamps.

- [ ] **Step 4: Add the disposable demo and automated evidence document**

The demo prints each action, head set, conflict choice, delivery state, and final SHA-256. The evidence document records the exact test command and labels it automated simulation, not Android runtime proof.

- [ ] **Step 5: Run full tests, demo, and commit Task 6**

Run: `PYTHONPATH=src python3 -m unittest discover -s tests -v`

Run: `scripts/run-two-device-demo.sh "$(mktemp -d)"`

Expected: full suite PASS; demo ends with equal current revision IDs and body hashes.

```bash
git add tests/test_bidirectional_sync.py tests/fixtures scripts/run-two-device-demo.sh docs/milestone1/AUTOMATED_EVIDENCE.md
git commit -m "test: prove deterministic two-device convergence"
```

### Task 7: Android Build Skeleton and Compatible Revision Graph

**Files:**
- Create: `android-probe/settings.gradle.kts`
- Create: `android-probe/build.gradle.kts`
- Create: `android-probe/gradle.properties`
- Create: `android-probe/gradlew`
- Create: `android-probe/gradlew.bat`
- Create: `android-probe/gradle/wrapper/gradle-wrapper.properties`
- Create: `android-probe/gradle/wrapper/gradle-wrapper.jar`
- Create: `android-probe/app/build.gradle.kts`
- Create: `android-probe/app/src/main/AndroidManifest.xml`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/RevisionEnvelope.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/RevisionGraph.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/RevisionEnvelopeTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/RevisionGraphTest.java`

**Interfaces:**
- Consumes: the exact canonical envelope and head rules from Tasks 1-3.
- Produces: Java `RevisionEnvelope.parse(byte[])`, `serialize()`, `create(...)`, and getters matching the Python fields.
- Produces: Java `RevisionGraph.add(RevisionEnvelope)`, `heads(noteId)`, `pending(noteId)`, and `resolve(noteId, selectedRevisionId, resolverDevice, modifiedUtc)`.

- [ ] **Step 1: Satisfy the Android toolchain gate outside the repository**

Required commands/versions: JDK 17 `java` and `javac`; Android SDK platform 35; build-tools 35.0.0; Gradle 8.9 bootstrap. Keep JDK/SDK/cache content outside Git. Generate and commit the standard Gradle 8.9 wrapper; verify its distribution checksum before use.

If acquisition is blocked, record the exact missing tool or network permission and stop Android build claims. Do not substitute source inspection for an APK build.

- [ ] **Step 2: Create minimal build configuration**

Use namespace/application ID `org.reasonnotes.probe`, `minSdk 26`, `compileSdk 35`, `targetSdk 35`, Java 17, JUnit 4.13.2, AndroidX Core 1.15.0, and DocumentFile 1.0.1. Do not add Compose, Kotlin, AppCompat, analytics, or Google services.

- [ ] **Step 3: Write Java tests before production classes**

Port fixed cross-language vectors from Python tests for canonical bytes, revision/body hashes, Unicode, sorted multi-parents, unknown-parent pending state, resolution heads, stale resolution, and late ancestors.

- [ ] **Step 4: Run Android unit tests and verify RED**

Run: `cd android-probe && ./gradlew testDebugUnitTest --tests 'org.reasonnotes.probe.RevisionEnvelopeTest' --tests 'org.reasonnotes.probe.RevisionGraphTest'`

Expected: FAIL because the Java production classes do not exist.

- [ ] **Step 5: Implement the Java envelope and graph**

Match Python canonical UTF-8 serialization byte-for-byte. Do not introduce a second format interpretation or timestamp winner.

- [ ] **Step 6: Run Python vectors and Android unit tests and verify GREEN**

Run: `PYTHONPATH=src python3 -m unittest tests.test_envelope tests.test_graph -v`

Run: `cd android-probe && ./gradlew testDebugUnitTest`

Expected: both suites PASS on identical vectors.

- [ ] **Step 7: Commit Task 7**

```bash
git add android-probe
git commit -m "feat: add Android revision compatibility probe"
```

### Task 8: Android SAF Import and Durable Probe Repository

**Files:**
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/ProbeRepository.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/ExchangeDirectory.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/ProbeRepositoryTest.java`
- Create: `android-probe/app/src/androidTest/java/org/reasonnotes/probe/ExchangeDirectoryInstrumentedTest.java`

**Interfaces:**
- Produces: `ProbeRepository.open(File root)`, `importEnvelope(byte[], sourceName)`, `reconcile()`, `current(noteId)`, `heads(noteId)`, and `createProbeRevision(...)`.
- Produces: `ExchangeDirectory.persistGrant(Context, Uri)`, `savedTree(Context)`, and `scan(Context, ProbeRepository) -> ScanReport`.
- Internal repository order matches Python: history, then atomic index, then atomic current-note/status view.

- [ ] **Step 1: Write failing host repository tests**

Cover sequential import, duplicate rename, conflict preservation, multi-parent convergence, tombstone state, unknown parent, and reconciliation from history after stale generated state.

- [ ] **Step 2: Run host tests and verify RED**

Run: `cd android-probe && ./gradlew testDebugUnitTest --tests 'org.reasonnotes.probe.ProbeRepositoryTest'`

Expected: FAIL because `ProbeRepository` is missing.

- [ ] **Step 3: Implement the durable Android repository**

Use app-internal files for history/state. Use `FileDescriptor.sync()` before atomic renames where supported. Keep every imported competing revision.

- [ ] **Step 4: Write the SAF instrumentation test**

Test persisted read/write grant handling, enumeration of Markdown documents, unrelated-file filtering, successful import, rejected partial envelope, and reopening with the saved tree URI. Use an isolated test tree chosen for the probe; never the user's general Downloads root.

- [ ] **Step 5: Implement `ExchangeDirectory` and run available tests**

Run host tests: `cd android-probe && ./gradlew testDebugUnitTest`

Run physical instrumentation only when the phone is connected and the test tree is granted: `cd android-probe && ./gradlew connectedDebugAndroidTest`

Expected: host suite PASS; instrumentation result is reported separately as PASS, FAIL, or NOT RUN.

- [ ] **Step 6: Commit Task 8**

```bash
git add android-probe/app/src/main android-probe/app/src/test android-probe/app/src/androidTest
git commit -m "feat: import Android revisions from SAF"
```

### Task 9: Android KDE Connect Sender, Retry State, and Minimal Activity

**Files:**
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/DeliveryLedger.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/KdeConnectSender.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/MainActivity.java`
- Create: `android-probe/app/src/main/res/xml/file_paths.xml`
- Create: `android-probe/app/src/main/res/values/strings.xml`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/DeliveryLedgerTest.java`
- Create: `android-probe/app/src/androidTest/java/org/reasonnotes/probe/KdeConnectSenderInstrumentedTest.java`
- Modify: `android-probe/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: Android ledger behavior equivalent to Task 4 with maximum three normal/explicit-retry requests.
- Produces: `KdeConnectSender.buildIntent(Context, List<Uri>, deviceId) -> Intent` and `request(Activity, List<Uri>, deviceId) -> RequestOutcome`.
- Explicit component: package `org.kde.kdeconnect_tp`, class `org.kde.kdeconnect.plugins.share.ShareActivity`; action is `ACTION_SEND` or `ACTION_SEND_MULTIPLE`; URI grants use `ClipData` and `FLAG_GRANT_READ_URI_PERMISSION`.
- `MainActivity` actions: choose exchange directory, create deterministic probe note, `Sync now`, retry unconfirmed, and show current heads/bodies plus separate event labels.

- [ ] **Step 1: Write failing Android ledger tests**

Port the bounded request/retry/needs-attention cases from Python. Verify an activity launch records only `transfer-requested` and leaves content in history.

- [ ] **Step 2: Run ledger tests and verify RED**

Run: `cd android-probe && ./gradlew testDebugUnitTest --tests 'org.reasonnotes.probe.DeliveryLedgerTest'`

Expected: FAIL because the ledger is missing.

- [ ] **Step 3: Implement ledger and pure intent construction**

Keep request construction separate from `startActivity` so host tests can inspect action, component, extras, URI list, MIME type, clip data, and flags.

- [ ] **Step 4: Add physical intent-resolution instrumentation**

On the POCO, verify the explicit activity resolves and is exported, the installed KDE Connect package is present, and the generated content URI can be opened under the temporary grant. This test does not call successful resolution “file transferred.”

- [ ] **Step 5: Implement the minimal activity**

Use platform widgets in one vertical layout. `onResume` scans/imports first. `Sync now` then sends only eligible locally created revisions. Show heads and readable previews; the polished side-by-side/vertical conflict chooser remains out of scope.

- [ ] **Step 6: Build and run non-live verification**

Run: `cd android-probe && ./gradlew testDebugUnitTest assembleDebug`

Expected: unit tests PASS and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 7: Commit Task 9**

```bash
git add android-probe
git commit -m "feat: add Android KDE Connect sync action"
```

### Task 10: Live Linux and POCO Acceptance, Recommendation, and Handoff

**Files:**
- Create: `docs/milestone1/PHONE_TEST_PROCEDURE.md`
- Create: `docs/milestone1/EVIDENCE_MATRIX.md`
- Create: `docs/milestone1/FEASIBILITY_REPORT.md`
- Modify: `README.md`
- Modify: `PROJECT_STATUS.md`
- Modify: `handoffs/FORGE_TO_QWEN.md`

**Interfaces:**
- Consumes: exact APK and Python commit built by Tasks 1-9.
- Produces: evidence labels `source-inspected`, `automated`, `Linux-live`, `Android-user-observed`, `failed`, and `pending`.
- Produces: one recommendation—KDE Connect, Syncthing, or built-in LAN synchronization—and the smallest Milestone 2 action.

- [ ] **Step 1: Freeze artifact identities before live testing**

Record Git SHA, APK SHA-256, Python version, KDE Connect Linux version, device ID, and build command. Confirm the POCO is reachable. Do not install or transfer unrelated files.

- [ ] **Step 2: Write the exact short physical procedure before running it**

The procedure must label each action and expected observation:

1. User enables the required install/debug path or manually installs the exact APK.
2. User creates/selects `ReasonNotes Exchange` once in the probe and once in KDE Connect Share settings.
3. Linux listener starts; Linux `sync` imports first and requests its pending test revision.
4. Android `Sync now` resumes/scans, proves the Linux revision ID/body is current, then requests its pending revision.
5. Linux signal/import proves the Android revision ID/body is current.
6. Both sides compare the full current head IDs and body SHA-256 values before marking non-conflicting convergence.
7. Duplicate, offline, interrupted-transfer, conflict, keep-Linux, keep-Android, skip, deletion/edit, stale-resolution, and late-ancestor cases run as separately labeled fixtures.

If direct user interaction is required, stop at each labeled action, ask for only that action, then inspect logs/state before the next action.

- [ ] **Step 3: Run fresh automated verification**

Run: `PYTHONPATH=src python3 -m unittest discover -s tests -v`

Run: `scripts/run-two-device-demo.sh "$(mktemp -d)"`

Run: `cd android-probe && ./gradlew testDebugUnitTest assembleDebug`

Expected: all automated tests PASS, demo converges, APK builds. Any failure stops completion claims and is recorded by name.

- [ ] **Step 4: Run Linux-live and phone tests without overclaiming**

Use only the dedicated exchange folder and generated test revisions. Record whether direct Android targeting bypasses the chooser, every visible transition, incoming discovery without file-manager import, offline behavior, and whether one Linux action plus one Android action suffices for the non-conflict case. A sender request is not completion; receiver file arrival is not ReasonNotes import; one-way success is not bidirectional convergence.

- [ ] **Step 5: Write the evidence matrix and feasibility recommendation**

The report must state tests passed, failed, and not run; Android behavior actually observed; exact interaction count; limitations; retry/acknowledgement risk; and why the recommendation meets the priority order of no loss, predictability, simplicity, recoverability, and minimal maintenance.

- [ ] **Step 6: Define the actionable Milestone 2 plan**

If KDE Connect passes, plan only the minimal notes CRUD/search shell around the proven sync interfaces and decide whether receipts are needed. If it fails, select the simpler verified fallback and plan only its first end-to-end slice. Do not commit to Flutter unless Milestone 1 evidence supports the required platform interfaces.

- [ ] **Step 7: Update status and directional handoff**

Keep both documents operational: branch and last verified pre-documentation commit, new evidence, deployment/install state, unresolved uncertainty, do-not-redo items, and the smallest next action. Report the final documentation commit separately after push.

- [ ] **Step 8: Verify, commit, push, and verify the remote ref**

Run:

```bash
git status --short
git diff --check
PYTHONPATH=src python3 -m unittest discover -s tests -v
(cd android-probe && ./gradlew testDebugUnitTest assembleDebug)
git add README.md PROJECT_STATUS.md handoffs/FORGE_TO_QWEN.md docs/milestone1
git commit -m "docs: report KDE Connect feasibility result"
git push handoff HEAD:refs/heads/forge/kdeconnect-feasibility
git ls-remote handoff refs/heads/forge/kdeconnect-feasibility
```

Expected: the remote SHA exactly matches local `HEAD`. Report the final hash separately; do not write a commit's own hash into that commit.

## Current Implementation Blockers

1. The Fedora host has OpenJDK runtime 25 but no `javac`, Android SDK, Gradle, or ADB. Task 7 requires a JDK 17 compiler and pinned Android toolchain outside Git. Network/download approval or user-provided tooling may be needed.
2. Android intent routing, persisted directory grants, KDE Connect completion UI, OEM lifecycle behavior, and actual convergence require direct interaction with the POCO X6 5G. They cannot be completed unattended from the present host state.
3. KDE Connect exposes no ReasonNotes import acknowledgement. Milestone 1 deliberately tests a bounded explicit-retry workflow; whether receipts are necessary remains a physical usability decision, not an implementation assumption.
