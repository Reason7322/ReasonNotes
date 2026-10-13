# Milestone 2 Minimal Notes Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the proven synchronization core into a minimal usable Fedora and Android notes slice with CRUD, search, automatic local saving, explicit conflict choices, and trustworthy receipt status.

**Architecture:** Keep the proven Python core on Fedora and native Java core on Android. Add a small idempotent receipt envelope so only a receiving ReasonNotes instance can confirm durable import, then place thin GTK 4 and Android views over the existing immutable histories. Preserve the Milestone 1 probe artifact; do not adopt Flutter in this slice because it would require a third synchronization implementation or platform bridges before the product behavior is settled.

**Tech Stack:** Python 3.14 standard library, `unittest`, PyGObject/GTK 4.22, KDE Connect 26.08.1; native Android Java, AndroidX Core and DocumentFile, Gradle 8.9, JDK 17, SDK 35.

**Spec:** `docs/superpowers/specs/2026-10-12-kde-connect-feasibility-design.md`

## Global Constraints

- Account-free, offline-first, local-network-only operation; no cloud, Google services, subscriptions, telemetry, or background-service workaround.
- User notes remain readable Markdown; immutable revisions and tombstones remain the durable source of truth.
- Never infer delivery confirmation from a transfer request or KDE Connect completion.
- Conflicts require Keep Linux, Keep Android, or Skip; never select by timestamp or insert conflict markers.
- Every keep resolution acknowledges all heads observed by the resolver and retains superseded revisions.
- Default sync remains one user action per device; duplicate and out-of-order packets are idempotent.
- The frozen Milestone 1 APK and evidence directories are not modified.

## Review Focus

- A malformed, unexpected-peer, or unknown-revision receipt must not confirm a revision; Task 1 tests rejection and target binding. Receipts are delivery evidence, not authentication, and never delete revision history.
- Autosave racing with sync must create a complete revision or no revision, never a partial working file; Task 2 tests atomic save and restart recovery.
- Search must exclude deleted notes and include non-ASCII text without depending on network state; Tasks 2 and 4 test both platforms.
- A new remote head arriving while a conflict dialog is open must make the attempted stale resolution fail safely; Tasks 3 and 4 test revalidation at selection time.
- Process death between durable history and generated-view updates must rebuild the same list, editor, and conflict state; Tasks 2 and 4 test restart reconciliation.

---

### Task 1: Durable Import Receipts

**Files:**
- Create: `src/reasonnotes_sync/receipt.py`
- Create: `tests/test_receipt.py`
- Modify: `src/reasonnotes_sync/delivery.py`
- Modify: `src/reasonnotes_sync/sync.py`
- Modify: `src/reasonnotes_sync/cli.py`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/ReceiptEnvelope.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/DeliveryLedger.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/ExchangeDirectory.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/ReceiptStore.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/ReceiptEnvelopeTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/ReceiptStoreTest.java`
- Modify: `android-probe/app/src/test/java/org/reasonnotes/probe/DeliveryLedgerTest.java`

**Interfaces:**
- Produces: canonical `ReceiptEnvelope(note_id, revision_id, acknowledging_device, destination_device, imported_utc)` with deterministic UTF-8 serialization and SHA-256 identity.
- Produces: `DeliveryState.CONFIRMED` only after a valid receipt names an existing ledger entry for the expected peer.
- Produces: scan results that report receipts separately from note revisions.

The canonical LF-terminated metadata order is
`reasonnotes-receipt-format: 1`, `receipt-id`, `note-id`, `revision-id`,
`acknowledging-device`, `destination-device`, and `imported-utc`. One envelope
acknowledges one revision. `receipt-id` is SHA-256 over the same canonical
metadata with the `receipt-id` line omitted. There is no body. The complete
lifecycle is fixed in `docs/milestone2/RECEIPT_LIFECYCLE.md`.

- [ ] **Step 1: Write failing Python receipt and ledger tests**

Cover canonical round-trip, note/revision mismatch, malformed hash, wrong sender/receiver, unknown or never-requested revision, duplicate receipt, durable-import-only generation, interrupted confirmation recovery, offline receipt retry, and `transfer-requested` remaining unconfirmed without a receipt.

- [ ] **Step 2: Run the focused Python tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_receipt tests.test_delivery tests.test_sync tests.test_conflicts -v`

Expected: FAIL because receipt parsing and `confirmed` state do not exist.

- [ ] **Step 3: Implement the Python receipt path**

Generate a receipt only after `Repository.import_bytes` completes its durable history write and reconciliation. Store receipts outside note history, send them at most once by default, allow bounded explicit retry, and never require a receipt for another receipt.

- [ ] **Step 4: Write and run failing Android receipt tests**

Run from `android-probe`: `./gradlew testDebugUnitTest --tests '*ReceiptEnvelopeTest' --tests '*ReceiptStoreTest' --tests '*DeliveryLedgerTest'`

Expected: FAIL because Android receipt support does not exist.

- [ ] **Step 5: Implement Android receipt parsing, emission, and confirmation**

Use the same canonical vectors as Python. `ExchangeDirectory.scan` must classify revision, receipt, unrelated, and rejected files independently.

- [ ] **Step 6: Run both focused suites and commit**

```bash
git add src/reasonnotes_sync tests android-probe/app/src
git commit -m "feat: confirm durable imports with receipt envelopes"
```

### Task 2: Note Application Service

**Files:**
- Create: `src/reasonnotes_app/__init__.py`
- Create: `src/reasonnotes_app/service.py`
- Create: `tests/test_note_service.py`
- Modify: `pyproject.toml`

**Interfaces:**
- Consumes: `Repository.create_revision`, `Repository.graph`, and `resolve_conflict`.
- Produces: `NoteService.list_notes(query: str = "")`, `open_note(note_id)`, `create_note(body)`, `save_note(note_id, body)`, `delete_note(note_id)`, `conflict(note_id)`, and `resolve(note_id, choice, revision_id=None)`.
- Produces: `NoteSummary(note_id, title, preview, conflicted, modified_utc)`; title is the first non-empty Markdown line with leading `#` and whitespace removed.

- [ ] **Step 1: Write failing service tests**

Test create/edit/delete, Unicode case-insensitive search, deleted-note exclusion, autosave coalescing, edit refusal during conflict, stale-choice rejection, and restart recovery after history-only interruption.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_note_service -v`

Expected: FAIL because `reasonnotes_app.service` does not exist.

- [ ] **Step 3: Implement the service without a second persistence model**

The service delegates durable writes to `Repository`; it may cache only generated summaries. Saving identical text creates no new revision. Deletion creates a tombstone.

- [ ] **Step 4: Run the service and synchronization suites and commit**

Run: `PYTHONPATH=src python3 -m unittest tests.test_note_service tests.test_repository tests.test_conflicts tests.test_sync -v`

```bash
git add pyproject.toml src/reasonnotes_app tests/test_note_service.py
git commit -m "feat: add minimal note application service"
```

### Task 3: Fedora GTK Shell and Desktop Conflict Choice

**Files:**
- Create: `linux-app/reasonnotes.py`
- Create: `linux-app/ui.py`
- Create: `tests/test_linux_presenter.py`
- Create: `src/reasonnotes_app/presenter.py`

**Interfaces:**
- Consumes: `NoteService` and the existing Linux sync CLI functions.
- Produces: note list, Markdown text editor, search field, delete action, Sync action, visible delivery state, and a side-by-side conflict view with Keep Linux, Keep Android, and Skip.

- [ ] **Step 1: Write failing presenter tests**

Assert list/search mapping, debounced save dispatch, separate requested/completed/imported/confirmed labels, side-by-side device labels and full previews, Skip making no write, and stale-choice revalidation.

- [ ] **Step 2: Run presenter tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_linux_presenter -v`

- [ ] **Step 3: Implement the presenter and minimal GTK 4 shell**

Keep GTK callbacks thin. Autosave after 500 ms of inactivity and on note switch/window close. Sync scans before sending and never hides unresolved conflicts.

- [ ] **Step 4: Run presenter tests and a disposable GTK smoke test**

Use an isolated repository and confirm create, edit, restart, search, delete, and all three conflict actions without touching the user's notes.

- [ ] **Step 5: Commit**

```bash
git add linux-app src/reasonnotes_app/presenter.py tests/test_linux_presenter.py
git commit -m "feat: add minimal Fedora notes shell"
```

### Task 4: Android Notes Shell and Vertical Conflict Choice

**Files:**
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/NoteService.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/NotesActivity.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/ConflictActivity.java`
- Modify: `android-probe/app/src/main/AndroidManifest.xml`
- Modify: `android-probe/app/src/main/res/values/strings.xml`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/NoteServiceTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/ConflictActivityLayoutTest.java`

**Interfaces:**
- Consumes: `ProbeRepository`, `RevisionGraph`, `DeliveryLedger`, `ExchangeDirectory`, and `KdeConnectSender`.
- Produces: note list, editor, search, deletion, automatic local save, Sync now, visible delivery state, and a vertically stacked conflict view with both device labels, both content previews, Keep Linux, Keep Android, and Skip.

- [ ] **Step 1: Write failing Android service and conflict-layout tests**

Test CRUD/search parity with Python, deleted-note exclusion, process-restart reconciliation, exact conflict labels/bodies, Skip, both keep choices, and rejection after a third head arrives.

- [ ] **Step 2: Run the focused Android tests and verify RED**

Run from `android-probe`: `./gradlew testDebugUnitTest --tests '*NoteServiceTest' --tests '*ConflictActivityLayoutTest'`

- [ ] **Step 3: Implement the minimal native views**

Use standard Android widgets and the existing SAF/KDE Connect integration. Do not add a background service, rich Markdown rendering, formatting toolbar, or framework migration.

- [ ] **Step 4: Run Android unit tests, build a distinguishable APK, and commit**

Run from `android-probe`: `./gradlew testDebugUnitTest assembleDebug`

```bash
git add android-probe/app/src
git commit -m "feat: add minimal Android notes shell"
```

### Task 5: Two-Device Acceptance and Milestone Decision

**Files:**
- Create: `docs/milestone2/ACCEPTANCE_PROCEDURE.md`
- Create: `docs/milestone2/RESULTS.md`
- Modify: `docs/milestone1/FEASIBILITY_REPORT.md`
- Modify: `PROJECT_STATUS.md`
- Modify: `handoffs/FORGE_TO_QWEN.md`

**Interfaces:**
- Consumes: the receipt path and both minimal shells.
- Produces: evidence for one-action exchange, confirmed receipts, CRUD/search/autosave, conflict UI, offline recovery, and exact cross-device convergence.

- [ ] **Step 1: Write a labeled disposable-note procedure**

Include one-time setup, Linux edit, Android edit, deletion, offline concurrent edit, all three conflict actions, dropped receipt retry, duplicate delivery, restart during generated-view update, and exact head/body/hash comparison.

- [ ] **Step 2: Run automated verification**

Run the complete Python and Android unit suites and build the APK. Record exact commands, counts, source SHA, artifact path, and SHA-256.

- [ ] **Step 3: Run physical acceptance on the POCO**

Require one Linux Sync and one Android Sync now per exchange. Treat transfer request, KDE completion, ReasonNotes import, and receipt confirmation as four separate observations.

- [ ] **Step 4: Record results and commit**

Recommend continuing the proven native shells only if notes remain recoverable and both devices converge without file-manager work after setup.

```bash
git add docs/milestone2 docs/milestone1/FEASIBILITY_REPORT.md PROJECT_STATUS.md handoffs/FORGE_TO_QWEN.md
git commit -m "docs: record Milestone 2 acceptance"
```
