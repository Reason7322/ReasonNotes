# Android Task 4 Notes Application Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the existing Android synchronization probe into a safe native notes application with durable drafts, card-based navigation, local pins, explicit conflict handling, and selective KDE Connect synchronization.

**Architecture:** Keep `ProbeRepository` and its immutable revisions as the only note authority. Add a pure-Java application layer (`NoteDocument`, `NoteService`, draft/pin/preferences stores, and synchronization planner), then put standard Android Java activities and views over it. UI state and recovery data remain separate from synchronized Markdown history, while all repository writes use explicitly observed heads and fail closed when stale.

**Tech Stack:** Java 17, Android SDK 35/minimum SDK 26, AndroidX Core and DocumentFile, standard Android views, JUnit 4, Gradle 8.9, SAF, and the existing KDE Connect sender.

**Spec:** `docs/superpowers/specs/2026-10-15-android-task4-notes-shell-design.md`

## Global Constraints

- Preserve application ID `org.reasonnotes.probe`, existing internal repository/delivery paths, `reasonnotes-exchange` preferences, `tree-uri`, and the existing target-device default.
- Do not install, uninstall, clear, migrate, or inspect private data on the POCO during Task 4.
- Do not add Compose, Flutter, cloud transport, rich text, a second persistence model, or cross-device pin synchronization.
- Keep transfer requested, transport completion, durable import, and receipt confirmation as distinct evidence states.
- Run all development tests against temporary repositories and fake adapters.
- Complete, commit, push, and remotely verify Task 4 before opening the separate White/Dark/LiquidGlass theme checkpoint.
- Treat screenshots, builds, and automated tests as supporting evidence only; physical runtime and visual acceptance remain pending.

## Review Focus

Reviewers should concentrate on these high-risk invariants:

1. An unresolved edit-versus-delete conflict remains discoverable even if the current generated view is deleted, missing, or cannot supply display content.
2. A late completion from autosave generation N cannot remove or replace draft generation N+1; restart and stale-base paths preserve exact user text.
3. Every save, delete, or Keep action compares the complete observed head set immediately before writing.
4. Selected synchronization filters ordinary outgoing notes without filtering incoming scans or receipts, and retains required ancestry and tombstones without abusing forced retry.
5. The debug APK keeps the existing package/data contract, and a future signature mismatch must fail closed rather than lead to uninstall or data clearing.

---

### Task 1: Add compatible Markdown documents and guarded repository writes

**Files:**

- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/NoteDocument.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/ProbeRepository.java`
- Modify only if required: `android-probe/app/src/main/java/org/reasonnotes/probe/RevisionGraph.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/NoteDocumentTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/ProbeRepositoryApplicationTest.java`

- [ ] **Step 1: Write failing document-contract tests**

Cover first-nonempty-line title extraction, body extraction, deliberate title replacement/insertion, body changes that preserve the title/layout, Unicode text, empty documents, and byte-for-byte unchanged legacy documents on read.

- [ ] **Step 2: Write failing repository application tests**

Cover history/note enumeration, a new revision against an exact single head, rejection after the head changes, an explicit tombstone revision, exact-parent conflict resolution, complete-history access, and ancestry enumeration. Assert rejected operations create no files or generated-view changes.

- [ ] **Step 3: Run the focused tests and confirm RED for missing APIs**

Run:

```bash
cd android-probe
./gradlew testDebugUnitTest --tests 'org.reasonnotes.probe.NoteDocumentTest' --tests 'org.reasonnotes.probe.ProbeRepositoryApplicationTest'
```

Expected: failure because the application contracts do not yet exist.

- [ ] **Step 4: Implement the minimum pure-Java contracts**

Implement `NoteDocument.split`, `changeTitle`, and `changeContent` to mirror `src/reasonnotes_app/note_document.py` without rewriting on read.

Add narrow repository methods for:

- complete note/revision enumeration;
- exact current-head snapshots;
- revision creation against an expected head set;
- tombstone creation through the same guarded path;
- conflict-resolution creation with every observed head as a parent;
- full revision and ancestry lookup for conflict and synchronization planning.

Use a dedicated stale-head exception carrying expected and observed revision IDs. Do not select a winner by timestamp and do not expose a write that bypasses head validation.

- [ ] **Step 5: Re-run the focused tests and confirm GREEN**

- [ ] **Step 6: Commit the repository application foundation**

```bash
git add android-probe/app/src/main/java/org/reasonnotes/probe/NoteDocument.java \
  android-probe/app/src/main/java/org/reasonnotes/probe/ProbeRepository.java \
  android-probe/app/src/main/java/org/reasonnotes/probe/RevisionGraph.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/NoteDocumentTest.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/ProbeRepositoryApplicationTest.java
git commit -m "feat(android): add guarded note repository operations"
```

### Task 2: Implement NoteService, search, deletion, and conflict semantics

**Files:**

- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/NoteService.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/NoteServiceTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/NoteServiceConflictTest.java`

- [ ] **Step 1: Define immutable service results in failing tests**

Exercise summaries, editable notes with base heads, conflict snapshots with complete candidate bodies/deletion state, and resolution results. Keep Android widgets out of these types.

- [ ] **Step 2: Add failing CRUD/search/restart tests**

Cover:

- create, open, title edit, body edit, and delete;
- exact Markdown serialization and legacy compatibility;
- no new revision when the serialized document is unchanged;
- restart reconstruction from immutable history;
- deleted-note exclusion after a fully resolved deletion;
- NFKC-normalized, locale-independent case-insensitive search, including Polish characters;
- rejection of an edit or deletion whose base heads are stale.

- [ ] **Step 3: Add failing edit-versus-delete tests in both directions**

Construct these histories from a common base:

1. Linux edit plus Android tombstone.
2. Linux tombstone plus Android edit.

For both, assert the unresolved note remains in navigation with conflict state even when the generated current file is missing or represents deletion. Assert all complete heads and their full immutable content/deletion markers are inspectable. Keeping the edit makes the note visible; keeping the tombstone removes it from ordinary navigation only after the resolution revision is durable.

Also cover multiple heads from one origin, Skip writing nothing, exact revision choice, and rejection/refresh when heads change after inspection.

- [ ] **Step 4: Run the focused service tests and confirm RED**

```bash
cd android-probe
./gradlew testDebugUnitTest --tests 'org.reasonnotes.probe.NoteServiceTest' --tests 'org.reasonnotes.probe.NoteServiceConflictTest'
```

- [ ] **Step 5: Implement NoteService**

Derive all summaries and editable state from `ProbeRepository`. Build navigation from note histories/head sets, not only generated current files, so unresolved deletion conflicts cannot disappear. Normalize searchable title/body text with `Normalizer.Form.NFKC` and `Locale.ROOT`. Delegate every mutation to the guarded repository API and return explicit saved/conflicted/deleted results.

- [ ] **Step 6: Re-run focused tests and commit**

```bash
git add android-probe/app/src/main/java/org/reasonnotes/probe/NoteService.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/NoteServiceTest.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/NoteServiceConflictTest.java
git commit -m "feat(android): add native note application service"
```

### Task 3: Implement generation-safe drafts, autosave, pins, and preferences

**Files:**

- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/DraftStore.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/EditorAutosaveController.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/LocalPreferences.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/AndroidLocalPreferences.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/PinStore.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/AppPreferences.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/DraftStoreTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/EditorAutosaveControllerTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/PinStoreTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/AppPreferencesTest.java`

- [ ] **Step 1: Write failing DraftStore persistence and race tests**

Each atomic draft record contains note ID, monotonically increasing generation, exact base revision ID, title, and body. Cover:

- exact Unicode/newline round-trip after reopening a new `DraftStore` instance;
- ordinary successful save removing the matching generation;
- stale-base recovery remaining present and marked unsafe to apply;
- corrupted/incomplete draft files failing closed without deleting evidence;
- generation N save completing after generation N+1 was written;
- generation N completion never deleting or replacing N+1;
- when safe, rebasing N+1 onto N's new saved revision without changing N+1 title/body;
- a remote/conflicting base change leaving N+1 intact and unre-based.

The completion API must take the generation and base captured when the save began; it may only remove that exact current generation. Use atomic file replacement for each draft update.

- [ ] **Step 2: Write failing autosave-controller tests**

Use an injected scheduler/executor and latches rather than wall-clock sleeps. Cover 500 ms coalescing, only one repository save in flight, a newer edit arriving during that save, automatic follow-up save of the newer generation, save failure retaining exact draft text, stale-head failure retaining the draft, explicit flush, and controller reconstruction after process restart.

- [ ] **Step 3: Write failing local-state tests**

Use a fake `LocalPreferences` adapter to prove pin persistence across instances, stale/deleted pin IDs not appearing in navigation, pin/unpin producing no note revisions, current target-device default preservation, and explicit preference edits. Do not expose the future cross-device pin toggle.

- [ ] **Step 4: Run the focused tests and confirm RED**

```bash
cd android-probe
./gradlew testDebugUnitTest \
  --tests 'org.reasonnotes.probe.DraftStoreTest' \
  --tests 'org.reasonnotes.probe.EditorAutosaveControllerTest' \
  --tests 'org.reasonnotes.probe.PinStoreTest' \
  --tests 'org.reasonnotes.probe.AppPreferencesTest'
```

- [ ] **Step 5: Implement durable local recovery and preferences**

Keep draft persistence separate from note history and synchronization. Make `EditorAutosaveController` the sole coordinator of debounce/in-flight transitions. Wrap Android `SharedPreferences` behind `LocalPreferences` so the stores remain unit-testable without device state.

- [ ] **Step 6: Re-run focused tests and commit**

```bash
git add android-probe/app/src/main/java/org/reasonnotes/probe/DraftStore.java \
  android-probe/app/src/main/java/org/reasonnotes/probe/EditorAutosaveController.java \
  android-probe/app/src/main/java/org/reasonnotes/probe/LocalPreferences.java \
  android-probe/app/src/main/java/org/reasonnotes/probe/AndroidLocalPreferences.java \
  android-probe/app/src/main/java/org/reasonnotes/probe/PinStore.java \
  android-probe/app/src/main/java/org/reasonnotes/probe/AppPreferences.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/DraftStoreTest.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/EditorAutosaveControllerTest.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/PinStoreTest.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/AppPreferencesTest.java
git commit -m "feat(android): protect local drafts and pin state"
```

### Task 4: Build the card-based home and safe editor

**Files:**

- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/UiTokens.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/HomeLayout.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/NoteCardView.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/EditorActivity.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/MainActivity.java`
- Modify: `android-probe/app/src/main/AndroidManifest.xml`
- Modify: `android-probe/app/src/main/res/values/strings.xml`
- Modify or create only centralized UI resources beneath: `android-probe/app/src/main/res/`
- Modify: `android-probe/app/src/test/java/org/reasonnotes/probe/MainActivityLayoutTest.java`
- Modify: `android-probe/app/src/test/java/org/reasonnotes/probe/MainActivityRoutingTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/EditorActivityContractTest.java`

- [ ] **Step 1: Extend layout/contract tests before changing activities**

Verify two-column width allocation, minimum touch targets, status/navigation/IME inset calculations, bottom search anchoring, Pinned/Others partitioning, title-only/preview display contracts, conflict indicators, manifest routing, and the absence of floating New Note or nonfunctional controls.

- [ ] **Step 2: Implement centralized tokens and home composition**

Centralize temporary colors, typography, spacing, corner radii, icon tint, and state colors in `UiTokens`/resources so the later theme milestone can replace tokens without rebuilding screen structure. Preserve the existing Android 16 edge-to-edge inset correction.

Make `MainActivity` assemble the header/Settings action, full-width New Note, Pinned and Others two-column grids, and bottom search. Long press exposes only working Pin/Unpin and confirmed Delete actions. Route conflicted summaries, including tombstone conflicts, to `ConflictActivity` rather than unsafe editing.

- [ ] **Step 3: Implement the editor around DraftStore and autosave controller**

Provide editable title and Markdown body, explicit local-save/delivery status, back navigation, and an implemented-only overflow menu. Save on debounce, pause, and back without blocking the UI thread. Never show Saved before the repository write succeeds. On stale base or incoming conflict, keep exact text in `DraftStore`, disable unsafe writes, and direct the user to conflict/recovery handling.

- [ ] **Step 4: Run UI contract and core safety tests**

```bash
cd android-probe
./gradlew testDebugUnitTest \
  --tests 'org.reasonnotes.probe.MainActivityLayoutTest' \
  --tests 'org.reasonnotes.probe.MainActivityRoutingTest' \
  --tests 'org.reasonnotes.probe.EditorActivityContractTest' \
  --tests 'org.reasonnotes.probe.EditorAutosaveControllerTest' \
  --tests 'org.reasonnotes.probe.NoteServiceTest'
```

- [ ] **Step 5: Commit the home/editor slice**

```bash
git add android-probe/app/src/main android-probe/app/src/test/java/org/reasonnotes/probe/MainActivityLayoutTest.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/MainActivityRoutingTest.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/EditorActivityContractTest.java
git commit -m "feat(android): add notes home and autosaving editor"
```

### Task 5: Add Settings and full-content conflict UI — Checkpoint A

**Files:**

- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/SettingsActivity.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/ConflictActivity.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/MainActivity.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/EditorActivity.java`
- Modify: `android-probe/app/src/main/AndroidManifest.xml`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/SettingsActivityContractTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/ConflictActivityContractTest.java`

- [ ] **Step 1: Write failing Settings and conflict-view contract tests**

Settings tests cover real exchange-directory launch/status, editable KDE device ID, implemented ordering behavior, About/version text, and absence of fake appearance/privacy/shared-pin controls.

Conflict tests cover a vertically scrolling view, full untruncated content, Linux/Android and exact revision labels, multiple candidates from one origin, explicit deletion cards, Keep only on eligible Linux/Android candidates, and Skip with no write. Include both edit-versus-delete directions and a conflict whose current generated view is absent.

- [ ] **Step 2: Implement Settings with existing SAF ownership intact**

Reuse `ExchangeDirectory` and its existing preference/key. Persist only deliberate device/order changes through `AppPreferences`. Do not clear or replace a URI grant automatically.

- [ ] **Step 3: Implement ConflictActivity with last-moment revalidation**

Load complete candidate content from immutable revisions. Immediately before Keep, compare the service's current head set with the snapshot. On mismatch, write nothing, retain any editor draft, and refresh the choices. Successful Keep creates one resolution child of all observed heads. Skip simply returns.

- [ ] **Step 4: Run Checkpoint A focused tests**

```bash
cd android-probe
./gradlew testDebugUnitTest \
  --tests 'org.reasonnotes.probe.NoteDocumentTest' \
  --tests 'org.reasonnotes.probe.ProbeRepositoryApplicationTest' \
  --tests 'org.reasonnotes.probe.NoteServiceTest' \
  --tests 'org.reasonnotes.probe.NoteServiceConflictTest' \
  --tests 'org.reasonnotes.probe.DraftStoreTest' \
  --tests 'org.reasonnotes.probe.EditorAutosaveControllerTest' \
  --tests 'org.reasonnotes.probe.PinStoreTest' \
  --tests 'org.reasonnotes.probe.MainActivityLayoutTest' \
  --tests 'org.reasonnotes.probe.EditorActivityContractTest' \
  --tests 'org.reasonnotes.probe.SettingsActivityContractTest' \
  --tests 'org.reasonnotes.probe.ConflictActivityContractTest'
```

- [ ] **Step 5: Commit Checkpoint A and push it to the Task 4 branch**

```bash
git add android-probe/app/src/main android-probe/app/src/test
git commit -m "feat(android): complete native notes application core"
git push handoff HEAD:forge/android-notes-shell
git ls-remote handoff refs/heads/forge/android-notes-shell
```

Record the local and remote full SHA before beginning synchronization integration.

### Task 6: Add ordinary selected-note synchronization planning

**Files:**

- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/OutgoingSelection.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/SyncPlanner.java`
- Modify narrowly: `android-probe/app/src/main/java/org/reasonnotes/probe/DeliveryLedger.java`
- Modify narrowly if read APIs are missing: `android-probe/app/src/main/java/org/reasonnotes/probe/ReceiptStore.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/SyncPlannerTest.java`
- Modify: `android-probe/app/src/test/java/org/reasonnotes/probe/DeliveryLedgerTest.java`

- [ ] **Step 1: Write failing all-versus-selected planning tests**

Use independent note histories and delivery entries to prove:

- all-notes mode returns all ordinarily eligible outgoing revisions;
- selected mode returns no ordinary revision from an unselected note;
- selection is not translated into `forceIds`;
- required ancestry for a selected child is included when the receiver may still need it, within ordinary retry limits;
- pending tombstones for selected, previously shared notes are included;
- a selected tombstone does not pull unrelated note histories;
- duplicate/retry bounds remain effective;
- forced retry remains a separate explicit API and behavior;
- eligible receipts are unchanged for all, selected, or empty outgoing-note selections.

- [ ] **Step 2: Add catalogue tests**

Ensure the Choose notes catalogue is independent of home search and can represent ordinary, conflicted, and previously shared deleted notes that have an eligible tombstone. Use a safe title fallback from immutable history without resurrecting deleted content.

- [ ] **Step 3: Run focused sync-planning tests and confirm RED**

```bash
cd android-probe
./gradlew testDebugUnitTest --tests 'org.reasonnotes.probe.SyncPlannerTest' --tests 'org.reasonnotes.probe.DeliveryLedgerTest'
```

- [ ] **Step 4: Implement the planner as a pure boundary**

Represent all-notes and selected-note modes explicitly. Ask `DeliveryLedger` for ordinary eligibility with no force IDs, then filter/close dependencies by note ID. Plan receipts independently through `ReceiptStore`. Return an immutable plan describing revision, receipt, skipped, and already-confirmed IDs; do not invoke Android or KDE APIs here.

- [ ] **Step 5: Re-run focused tests and commit**

```bash
git add android-probe/app/src/main/java/org/reasonnotes/probe/OutgoingSelection.java \
  android-probe/app/src/main/java/org/reasonnotes/probe/SyncPlanner.java \
  android-probe/app/src/main/java/org/reasonnotes/probe/DeliveryLedger.java \
  android-probe/app/src/main/java/org/reasonnotes/probe/ReceiptStore.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/SyncPlannerTest.java \
  android-probe/app/src/test/java/org/reasonnotes/probe/DeliveryLedgerTest.java
git commit -m "feat(android): plan safe selected note synchronization"
```

### Task 7: Integrate scan-first synchronization and mobile Sync UI

**Files:**

- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/SyncCoordinator.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/SyncActivity.java`
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/SyncStatus.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/SettingsActivity.java`
- Modify only through existing APIs: `android-probe/app/src/main/java/org/reasonnotes/probe/ExchangeDirectory.java`
- Modify only through existing APIs: `android-probe/app/src/main/java/org/reasonnotes/probe/KdeConnectSender.java`
- Modify: `android-probe/app/src/main/AndroidManifest.xml`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/SyncCoordinatorTest.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/SyncActivityContractTest.java`

- [ ] **Step 1: Write failing coordinator-order and evidence tests**

Use fake scanner/planner/sender adapters to prove:

- active-draft flush happens first and aborts safely on failure;
- incoming revisions and receipts are scanned/imported before outgoing planning;
- incoming scanning and receipt processing still occur with no selected outgoing notes;
- imported heads can invalidate the editing base without replacing the draft;
- only files from the selected plan reach the sender;
- ledger transfer-requested state changes only after sender acceptance;
- rejected/failed requests retain eligibility according to existing retry rules;
- UI status distinguishes requested, imported, and receipt-confirmed counts.

- [ ] **Step 2: Write failing Sync UI contract tests**

Cover separate Sync all and Choose notes actions; an independent searchable checkbox catalogue; Select All/Clear Selection; disabled send for an empty selection; restoration across activity recreation; pending/unconfirmed/confirmed labels; progress/error states; and safe inset behavior.

- [ ] **Step 3: Run focused integration tests and confirm RED**

```bash
cd android-probe
./gradlew testDebugUnitTest --tests 'org.reasonnotes.probe.SyncCoordinatorTest' --tests 'org.reasonnotes.probe.SyncActivityContractTest'
```

- [ ] **Step 4: Implement asynchronous orchestration and views**

Perform file scans, reconciliation, planning, URI preparation, and sender requests on an executor; marshal only view updates to the main thread. Reuse the current `ExchangeDirectory` and `KdeConnectSender` rather than reimplementing transport. Preserve selection after recoverable errors and never label an accepted share intent as imported or confirmed.

- [ ] **Step 5: Run sync and unchanged receipt/transport suites**

```bash
cd android-probe
./gradlew testDebugUnitTest \
  --tests 'org.reasonnotes.probe.SyncPlannerTest' \
  --tests 'org.reasonnotes.probe.SyncCoordinatorTest' \
  --tests 'org.reasonnotes.probe.SyncActivityContractTest' \
  --tests 'org.reasonnotes.probe.ExchangeDirectoryTest' \
  --tests 'org.reasonnotes.probe.DeliveryLedgerTest' \
  --tests 'org.reasonnotes.probe.ReceiptEnvelopeTest' \
  --tests 'org.reasonnotes.probe.ReceiptStoreTest'
```

- [ ] **Step 6: Commit synchronization integration**

```bash
git add android-probe/app/src/main android-probe/app/src/test
git commit -m "feat(android): integrate selective receipt-aware sync"
```

### Task 8: Complete Task 4 verification, documentation, APK, and Checkpoint B

**Files:**

- Modify: `docs/superpowers/plans/2026-10-12-milestone2-minimal-notes-slice.md`
- Create: `docs/milestone2/ANDROID_TASK4.md`
- Modify: `PROJECT_STATUS.md`
- Modify tests/source only if verification demonstrates a Task 4 defect.

- [ ] **Step 1: Run the complete Android unit suite once**

```bash
cd android-probe
./gradlew testDebugUnitTest
```

Record exact passed/failed/skipped counts from the XML reports. If a test fails, use systematic debugging and re-run only the failing/focused class until fixed, then re-run the complete suite once because the candidate changed.

- [ ] **Step 2: Build the debug APK**

```bash
cd android-probe
./gradlew assembleDebug
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Record the exact APK path, byte size, SHA-256, application ID, version, and debug signing-certificate fingerprint. Do not install it.

- [ ] **Step 3: Perform static/runtime-safe packaging checks**

Verify the manifest contains all intended activities and preserves Android 16 inset behavior. Verify the package remains `org.reasonnotes.probe` and all established repository, delivery, receipt, exchange-preference, SAF-key, and target-device defaults remain compatible. Document that Task 5 must compare the installed and candidate signing identities and stop on mismatch; uninstall/clear-data is not a recovery step.

- [ ] **Step 4: Update implementation/status documents with evidence boundaries**

Update the Milestone 2 plan and `ANDROID_TASK4.md` with the implemented screen/data flows and test commands. Update `PROJECT_STATUS.md` with exact test count, APK/hash, branch/checkpoint placeholder, and these explicit limitations:

- no installation or physical POCO runtime verification;
- no two-device receipt confirmation acceptance;
- no visual acceptance of the temporary Android palette;
- shared pin synchronization deferred;
- White/Dark/LiquidGlass work not started.

- [ ] **Step 5: Review the final diff for scope and placeholders**

```bash
git diff --check
git status --short
rg -n 'TODO|FIXME|placeholder|not implemented' android-probe docs/milestone2 PROJECT_STATUS.md
```

Resolve accidental placeholders; retain only intentional deferred items documented as such.

- [ ] **Step 6: Commit and push Checkpoint B**

```bash
git add android-probe docs/superpowers/plans/2026-10-12-milestone2-minimal-notes-slice.md \
  docs/milestone2/ANDROID_TASK4.md PROJECT_STATUS.md
git commit -m "feat(android): complete Task 4 notes application"
git push handoff HEAD:forge/android-notes-shell
git ls-remote handoff refs/heads/forge/android-notes-shell
```

Confirm the full remote SHA equals local `HEAD` and report final `git status --short --branch`. Stop after Task 4. Do not create or begin the theme branch until this checkpoint has been reviewed as complete.
