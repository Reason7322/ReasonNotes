# Milestone 2 Minimal Notes Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the proven synchronization core into a minimal usable Fedora and Android notes slice with CRUD, search, automatic local saving, explicit conflict choices, and trustworthy receipt status.

**Architecture:** Keep the proven Python core on Fedora and native Java core on Android. Add a small idempotent receipt envelope so only a receiving ReasonNotes instance can confirm durable import, then place a thin Qt 6 / Qt Quick desktop view and native Android view over the existing immutable histories. Preserve the Milestone 1 probe artifact; do not adopt Flutter in this slice because it would require a third synchronization implementation or platform bridges before the product behavior is settled.

**Tech Stack:** Python 3.14 standard library, `unittest`, PySide6 6.11.2, Qt 6.11.2, Qt Quick/QML and Qt Quick Controls, KDE Connect 26.08.1; native Android Java, AndroidX Core and DocumentFile, Gradle 8.9, JDK 17, SDK 35.

**Spec:** `docs/superpowers/specs/2026-10-12-kde-connect-feasibility-design.md`

## Global Constraints

- Account-free, offline-first, local-network-only operation; no cloud, Google services, subscriptions, telemetry, or background-service workaround.
- User notes remain readable Markdown; immutable revisions and tombstones remain the durable source of truth.
- Never infer delivery confirmation from a transfer request or KDE Connect completion.
- Conflicts require Keep Linux, Keep Android, or Skip; never select by timestamp or insert conflict markers.
- Every keep resolution acknowledges all heads observed by the resolver and retains superseded revisions.
- Default sync remains one user action per device; duplicate and out-of-order packets are idempotent.
- The frozen Milestone 1 APK and evidence directories are not modified.
- Fedora Task 3 uses Qt 6 / Qt Quick / QML / PySide6; GTK and a Qt Widgets primary interface are out of scope.

## Review Focus

- A malformed, unexpected-peer, or unknown-revision receipt must not confirm a revision; Task 1 tests rejection and target binding. Receipts are delivery evidence, not authentication, and never delete revision history.
- Autosave racing with sync must create a complete revision or no revision and must preserve the displayed draft on a stale base; Tasks 2 and 3 test persistence and presenter behavior.
- Search must exclude deleted notes and include non-ASCII text without depending on network state; Tasks 2 and 4 test both platforms.
- A new remote head arriving while a conflict dialog is open must make the attempted stale resolution fail safely and refresh full candidate content; Tasks 3 and 4 test revalidation at selection time.
- Selective Fedora sync must still import incoming revisions and send receipts while excluding every unselected outgoing note revision; Task 3 tests all three paths independently.

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

### Task 3: Fedora Qt Quick Shell, Selective Sync, and Desktop Conflict Choice

**Spec:** `docs/superpowers/specs/2026-10-14-qt-quick-desktop-design.md`

**Files:**
- Create: `docs/superpowers/specs/2026-10-14-qt-quick-desktop-design.md`
- Modify: `src/reasonnotes_sync/sync.py`
- Modify: `src/reasonnotes_sync/cli.py`
- Create: `linux-app/reasonnotes.py`
- Create: `linux-app/qml/Main.qml`
- Create: `linux-app/qml/Theme.qml`
- Create: `linux-app/qml/Sidebar.qml`
- Create: `linux-app/qml/EditorPane.qml`
- Create: `linux-app/qml/SyncDialog.qml`
- Create: `linux-app/qml/ConflictView.qml`
- Create: `tests/test_linux_presenter.py`
- Create: `tests/test_selective_sync.py`
- Create: `tests/test_qml_smoke.py`
- Create: `src/reasonnotes_app/presenter.py`
- Create: `src/reasonnotes_app/qt_bridge.py`

**Interfaces:**
- Consumes: `NoteService`, `DeliveryLedger`, `sync_linux`, and `KdeConnectLinux`.
- Produces: `sync_linux(..., selected_note_ids: Collection[UUID] | None = None) -> SyncReport`; `None` selects every ordinary eligible note revision and an explicit collection filters only note revisions, never receipts or incoming scans.
- Produces: `NotePresenter` with event-driven create/load/edit/save/delete/search, pre-sync flush, delivery status, full conflict inspection, exact conflict resolution, and stale refresh behavior.
- Produces: PySide6 list models and bridge properties/signals/slots consumed by QML.
- Produces: a 1200 × 800 dark two-pane Qt Quick shell, persistent draggable sidebar, 500 ms autosave, keyboard shortcuts, selective-sync dialog, and full-content conflict panel.

- [x] **Step 1: Write failing selective-sync tests**

Assert all-note behavior is unchanged; explicit selection excludes other notes; all eligible ancestry and tombstones for selected notes are requested; incoming envelopes are scanned; and eligible receipts are sent even for an empty note selection.

- [x] **Step 2: Run selective-sync tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_selective_sync -v`

Expected: FAIL because `sync_linux` has no ordinary note selection.

- [x] **Step 3: Implement ordinary note selection in the synchronization layer**

Add `selected_note_ids` without changing `force_ids`. Filter only eligible note revisions after the inbox scan. Keep receipt eligibility independent. Add repeatable `--note-id` to the CLI sync command.

- [x] **Step 4: Run selective-sync and existing sync tests**

Run: `PYTHONPATH=src python3 -m unittest tests.test_selective_sync tests.test_sync tests.test_delivery tests.test_receipt -v`

- [x] **Step 5: Write failing presenter tests**

Test create/load/save, unchanged autosave, failed-save draft preservation, switch and pre-sync flush, draft preservation during sync completion, Unicode search, deletion, evidence-accurate delivery status, full immutable conflict bodies, deletion and multiple-head candidates, exact Keep actions, Skip, and stale-head refresh.

- [x] **Step 6: Run presenter tests and verify RED**

Run: `PYTHONPATH=src python3 -m unittest tests.test_linux_presenter -v`

Expected: FAIL because `reasonnotes_app.presenter` does not exist.

- [x] **Step 7: Implement the UI-independent presenter**

Keep all note writes in `NoteService`. Flush before switch, close, and sync. Preserve draft text and Error state on failure. Build conflict candidates from immutable revisions, not truncated previews.

- [x] **Step 8: Run presenter and service tests**

Run: `PYTHONPATH=src python3 -m unittest tests.test_linux_presenter tests.test_note_service -v`

- [x] **Step 9: Write the Qt bridge, QML shell, and failing offscreen smoke test**

Expose event-driven `QAbstractListModel` instances and properties through PySide6. Run sync work through `QThreadPool`. Centralize theme values in `Theme.qml`; use `SplitView`, persist sidebar width, and implement the approved controls, shortcuts, sync dialog, and conflict view.

- [x] **Step 10: Run the real QML component tree offscreen**

Run: `QT_QPA_PLATFORM=offscreen PYTHONPATH=src python3 -m unittest tests.test_qml_smoke -v`

Expected: the real `Main.qml` tree loads without QML warnings or errors. This is not visual acceptance.

- [x] **Step 11: Run focused Task 3 verification and commit**

Run: `QT_QPA_PLATFORM=offscreen PYTHONPATH=src python3 -m unittest tests.test_selective_sync tests.test_linux_presenter tests.test_qml_smoke tests.test_note_service tests.test_sync tests.test_delivery tests.test_receipt tests.test_conflicts -v`

Use only disposable repository roots and fake adapters.

```bash
git add docs/superpowers src/reasonnotes_sync src/reasonnotes_app linux-app tests PROJECT_STATUS.md pyproject.toml
git commit -m "feat: add Qt Quick desktop notes shell"
```

### Task 4: Android Notes Shell and Vertical Conflict Choice — IMPLEMENTED / BUILD VERIFIED

The original minimal shell plan was superseded by the owner-approved
card-grid design and the focused implementation documents:

- Specification: `docs/superpowers/specs/2026-10-15-android-task4-notes-shell-design.md`.
- Detailed plan: `docs/superpowers/plans/2026-10-15-android-task4-implementation.md`.
- Implementation evidence and APK: `docs/milestone2/ANDROID_TASK4.md`.

**Task 4 source/build checkpoints:**

- [x] Implement `NoteDocument`, guarded `ProbeRepository`, `NoteService`, and regression tests for edit/delete conflicts, stale heads and missing generated views.
- [x] Implement generation-safe draft persistence, debounced autosave, local-only `PinStore`, and preferences.
- [x] Implement the native Home, Editor, Settings and vertically stacked Conflict screens with bottom search, card grid and safe insets.
- [x] Implement all/selected outgoing synchronization planner, scan-first coordinator, receipt-independent selection, and mobile Sync UI.
- [x] Complete the host JVM suite on implementation commit `6171312f54386e0f09203ab84199861591d95071`: **93/93 passed** on 2026-10-15.
- [x] Assemble, sign-verify, hash and preserve the Android debug APK; document compatibility checks and remaining installation gate.
- [x] Record source/build evidence and a final documentation-only checkpoint in the neutral Git repository.

This completes Task 4 **implementation and host verification only**. It does
not authorize or imply installation on the POCO, on-device visual/functional
acceptance, or a confirmed Milestone 2 receipt exchange. Those belong to
Task 5, which remains pending owner authorization. Cross-device pin
synchronization and White/Dark/LiquidGlass themes are separate future work.

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
