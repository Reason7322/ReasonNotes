# Simplified ReasonNotes Sync v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace history-wide ReasonNotes storage and synchronization with a current-state model that launches quickly, synchronizes current edits, presents edit/edit conflicts, and permanently propagates deletions.

**Architecture:** Preserve the existing JSON bundle and KDE Connect transfer path, but carry v2 current-state envelopes and exact acknowledgments. Each app stores one current record per live note, at most two temporary edit-conflict candidates, one coalesced pending state per note, transient outgoing acknowledgments, and content-free deletion fences. Each app performs a staged, validated, one-time conversion from the old repository before old active history can be removed.

**Tech Stack:** Android Java/Gradle/JUnit; Fedora Python/PySide6/pytest or unittest; canonical JSON; existing KDE Connect adapters and JSON transport bundle.

**Spec:** `docs/SIMPLIFIED_SYNC_V2_DESIGN_2026-10-24.md`

## Global Constraints

- Preserve the approved Fedora Qt Widgets/LiquidGlass interface and existing Android layout.
- Preserve the physically proven KDE Connect share path and JSON bundle container.
- No cloud service, account system, background daemon, global KDE Connect change, or new transport.
- Deletion permanently erases note title/body/history; only a content-free fence may remain.
- Deletion wins over any edit; conflict UI is only for independent live-note edits.
- Never choose a conflict winner by timestamp and never silently overwrite content.
- Import acknowledgment is created only after durable state persistence.
- Do not install, launch, migrate personal data, or run physical synchronization during implementation.
- Keep Android and Fedora commits/checkpoints independent and dual-remote verified.

## Review Focus

- Crash after current-note write but before pending-queue write must recover the unsent local state; covered in Tasks 2 and 5.
- Old live carrier arriving after deletion must not resurrect content; covered in Tasks 3 and 6.
- Repeated local edits before sync must send only the newest complete state; covered in Tasks 3 and 6.
- Duplicate or reordered carriers/acknowledgments must be idempotent; covered in Tasks 4 and 7.
- Migration encountering more than two live heads or missing parent content must stop without switching stores; covered in Tasks 2 and 5.

---

### Task 1: Canonical v2 wire records and cross-language fixtures

**Files:**
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/StateEnvelope.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/StateAckEnvelope.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/StateEnvelopeTest.java`
- Create: `android-probe/app/src/test/resources/sync-v2/*.json`
- Create in Fedora repo: `src/reasonnotes_sync/state_envelope.py`
- Create in Fedora repo: `tests/test_state_envelope.py`
- Copy canonical fixtures to Fedora repo: `tests/fixtures/sync-v2/*.json`
- Copy the approved design into Fedora repo: `docs/SIMPLIFIED_SYNC_V2_DESIGN_2026-10-24.md`

**Interfaces:**
- Produces Android `StateEnvelope.createUpsert(...)`, `createDeletion(...)`, `parse(byte[])`, `serialize()` and `StateAckEnvelope.create(...)`, `parse(byte[])`, `serialize()`.
- Produces Python `StateEnvelope.upsert(...)`, `deletion(...)`, `from_bytes(...)`, `to_bytes()` and matching `StateAckEnvelope` APIs.
- `StateEnvelope` fields: format=`reasonnotes-state-v2`, note UUID, operation=`upsert|delete`, change ID, zero/one/two parent change IDs, origin device, UTC timestamp, title/body only for upsert.
- `StateAckEnvelope` fields: format=`reasonnotes-ack-v2`, note UUID, acknowledged change ID, destination device, UTC timestamp, deterministic acknowledgment ID.

- [ ] Write Java tests for canonical serialization, hash validation, deletion containing no title/body, malformed input rejection, and all shared fixtures.
- [ ] Run the Java tests and verify they fail because the v2 envelope classes do not exist.
- [ ] Implement the Java records with canonical JSON and content-addressed IDs.
- [ ] Run the Java tests and verify they pass.
- [ ] Write equivalent Python tests against byte-identical copied fixtures and verify they fail.
- [ ] Implement the Python records and verify every Java fixture round-trips byte-identically.
- [ ] Commit Task 1 independently in both repositories.

### Task 2: Android current-state store and restartable migration

**Files:**
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/CurrentNoteStore.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/LegacyRepositoryMigrator.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/CurrentNoteStoreTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/LegacyRepositoryMigratorTest.java`

**Interfaces:**
- `CurrentNoteStore.open(File root)`; `list()`, `get(UUID)`, `saveLocal(...)`, `apply(StateEnvelope)`, `resolve(UUID,String)`, `deleteLocal(UUID)`, `pendingStates()`, `pendingAcks()`, `acknowledge(String)`, and `conflict(UUID)`.
- Store directories: `current/`, `conflicts/`, `deleted/`, `pending/`, `outgoing-acks/`; all records use write-fsync-atomic-rename.
- `LegacyRepositoryMigrator.migrate(ProbeRepository legacy, File staging, File active)` returns a report and switches to v2 only after reopen/validation.

- [ ] Write store tests for durable create/edit/restart, coalesced pending state, conflict candidates, deletion content erasure, old-carrier rejection, acknowledgment clearing, and recovery when current is durable but pending is missing.
- [ ] Run the store tests and verify they fail because `CurrentNoteStore` is absent.
- [ ] Implement the minimal store and make those tests pass.
- [ ] Write migration tests for live notes, one two-head conflict, deleted-history erasure, interrupted staging, malformed/missing ancestry, and more-than-two-head refusal.
- [ ] Run migration tests RED, implement staged migration, then run GREEN.
- [ ] Verify migration tests inspect synthetic roots only and never reference Android application data.
- [ ] Commit Task 2 on the Android branch.

### Task 3: Android note service, launch path, deletion, and conflict adapter

**Files:**
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/NoteService.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/MainActivity.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/EditorActivity.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/ConflictActivity.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/BulkNoteDeletion.java`
- Test: existing service/activity/deletion/conflict tests plus new `CurrentStoreApplicationTest.java`

**Interfaces:**
- Consumes `CurrentNoteStore` from Task 2.
- Produces unchanged activity-facing `NoteService` behavior backed by current state rather than `ProbeRepository` graph scans.

- [x] Add failing tests proving launch listing reads only current records, repeated edits coalesce, edit/edit conflict exposes both complete versions, chosen content becomes pending, and deletion immediately removes all content.
- [x] Add a test proving deletion wins over a later stale upsert and no edit/delete conflict is created.
- [x] Adapt `NoteService` and activities without changing layout, labels, navigation, or editor behavior.
- [x] Run focused Android service/activity tests and verify they pass.
- [x] Add a performance contract using hundreds of synthetic legacy revisions but seven migrated current notes; assert v2 listing opens only current records and completes within the existing JVM budget.
- [x] Commit Task 3 on the Android branch.

### Task 4: Android v2 transport, acknowledgments, and understandable status

**Files:**
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/TransportBundle.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/ExchangeDirectory.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/SyncCoordinator.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/SyncActivity.java`
- Retire from v2 path: `DeliveryLedger.java`, `ReceiptStore.java`, `SyncPlanner.java`, `RevisionGraph.java`
- Test: `TransportBundleTest.java`, `ExchangeDirectoryTest.java`, `SyncCoordinatorTest.java`, `SyncActivityContractTest.java`

**Interfaces:**
- Consumes Task 1 envelopes and Task 2 pending APIs.
- Produces bundle item kinds `state-v2` and `ack-v2`; each sync operation snapshots current pending IDs and reports only their results.

- [x] Write failing tests for one-state-per-note output, state-before-ack import, durable-then-ack ordering, duplicate/reordered idempotence, independent-note progress after one failure, and current-operation-only status.
- [x] Implement v2 bundle classification/import/export while retaining v1 read compatibility.
- [x] Replace retry exhaustion with retryable pending state; no historical row may block another note.
- [x] Reduce visible status to synced, waiting for peer, transfer failed, or conflict, naming the affected live note where possible.
- [x] Run focused transport/sync/status tests, then the Android JVM suite and `assembleDebug`.
- [x] Record APK hash but do not install it.
- [x] Commit and dual-remote checkpoint the Android repository.

### Task 5: Fedora current-state store and restartable migration

**Files:**
- Create in Fedora repo: `src/reasonnotes_sync/current_store.py`
- Create: `src/reasonnotes_sync/legacy_migration.py`
- Create: `tests/test_current_store.py`
- Create: `tests/test_legacy_migration.py`

**Interfaces:**
- Python `CurrentNoteStore` mirrors Task 2 semantics and disk layout.
- `migrate_legacy_repository(old_root: Path, staging: Path, active: Path) -> MigrationReport` mirrors Android validation/refusal behavior.

- [ ] Write failing store tests equivalent to Android Task 2, including crash recovery and deletion content erasure.
- [ ] Implement the store and run tests GREEN.
- [ ] Write failing migration tests using existing synthetic repository fixtures.
- [ ] Implement staged migration with reopen/validation and atomic active selection.
- [ ] Add fixture-level parity assertions against Android migration outcomes.
- [ ] Commit Task 5 on the Fedora branch.

### Task 6: Fedora service and approved Widgets adapters

**Files:**
- Modify: `src/reasonnotes_app/service.py`
- Modify: `src/reasonnotes_app/presenter.py`
- Modify: `src/reasonnotes_widgets/controller.py`
- Modify only data/status wiring as necessary: `src/reasonnotes_widgets/conflict_page.py`, `sync_panel.py`
- Test: `tests/test_note_service.py`, `test_widgets_conflicts.py`, `test_widgets_sync_panel.py`

**Interfaces:**
- Consumes Task 5 store.
- Preserves existing presenter/controller signals and the owner-approved visual widget hierarchy.

- [ ] Add failing service tests for current-only listing, coalesced saves, complete two-version conflict selection, and permanent deletion.
- [ ] Add source/contract tests that protected sidebar/material/editor visual files are unchanged and conflict widgets still receive two complete versions.
- [ ] Adapt service/presenter/controller storage wiring without modifying styling or layout.
- [ ] Run focused service and offscreen Widgets tests.
- [ ] Commit Task 6 on the Fedora branch.

### Task 7: Fedora v2 transport and per-operation status

**Files:**
- Modify: `src/reasonnotes_sync/transport_bundle.py`
- Modify: `src/reasonnotes_sync/sync.py`
- Modify: `src/reasonnotes_sync/cli.py`
- Retire from v2 path: `delivery.py`, `receipt.py`, `graph.py`, historical ancestry planning in `repository.py`
- Test: `tests/test_sync_v2.py`, `test_bidirectional_sync.py`, `test_confirmed_sync.py`, `test_selective_sync.py`

**Interfaces:**
- Consumes Task 1 Python envelopes and Task 5 store.
- Produces the same v2 carrier and acknowledgment semantics as Android Task 4.

- [ ] Write failing tests for create/edit round trips, duplicate/reordered carriers, isolated per-note failure, exact acknowledgment clearing, and deletion non-resurrection.
- [ ] Implement v2 receive/send using existing KDE Connect adapter and JSON bundle container.
- [ ] Ensure status reflects only the current operation and old v1 ledger/receipt failures are ignored by v2.
- [ ] Retain v1 import compatibility without emitting new v1 revisions.
- [ ] Run focused synchronization and Widgets status tests.
- [ ] Commit Task 7 on the Fedora branch.

### Task 8: Cross-platform acceptance fixtures, documentation, and offline candidates

**Files:**
- Update both: `PROJECT_STATUS.md`
- Create in both repos: `docs/SIMPLIFIED_SYNC_V2_OFFLINE_ACCEPTANCE_2026-10-24.md`
- Add cross-language generated carriers under each test-fixture directory only.

**Interfaces:**
- Consumes completed Android and Fedora v2 implementations.
- Produces independently identifiable offline candidates and exact future deployment procedure.

- [ ] Generate Android carriers and import them in Fedora tests; generate Fedora carriers and import them in Android tests.
- [ ] Verify create, Android edit, edit/edit resolution, deletion in each direction, unreachable-peer retry, duplicate delivery, restart persistence, and acknowledgment convergence using disposable roots.
- [ ] Verify deleted title/body text is absent from active stores, pending queues, conflicts, and new carriers after deletion.
- [ ] Run the full relevant suites once because both storage and transport changed broadly; build Android APK and prepare an isolated Fedora candidate without launching either.
- [ ] Record exact commands, test counts, hashes, migration limitations, and remaining physical acceptance.
- [ ] Dual-remote checkpoint each repository and independently verify local/handoff/github full SHAs.
- [ ] STOP before installation, application launch, personal-data migration, KDE Connect transfer, or physical synchronization.

