# Direct Exchange Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import KDE Connect carriers directly from the fixed ReasonNotes exchange directory when Android All files access is enabled.

**Architecture:** Add a pure Java fixed-directory scanner that reuses the existing canonical carrier validator and current-state store. Android selects it when `Environment.isExternalStorageManager()` is true and otherwise retains SAF as a compatibility fallback. Settings exposes the special-access action and removes obsolete deletion pairing.

**Tech Stack:** Java 17, Android SDK 35, JUnit 4, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-25-direct-exchange-access-design.md`

## Global Constraints

- Scan only `/storage/emulated/0/ReasonNotes Exchange`.
- Preserve JSON v1 and v2 current-state semantics.
- Do not modify existing notes or ledgers during development.
- Do not install the resulting APK without exact-hash authorization.

## Review Focus

- Missing special access falls back safely without crashing.
- A valid carrier imports once and queues one acknowledgment.
- Unrelated and malformed files do not become notes.
- Repeated scans do not re-read successfully processed carriers.
- Settings contains no obsolete pairing-key controls.

---

### Task 1: Fixed-directory receiver

**Files:**
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/SharedExchangeDirectory.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/SharedExchangeDirectoryTest.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/IncomingReceive.java`

**Interfaces:**
- Produces: `SharedExchangeDirectory.scan(File, File, CurrentNoteStore, String, IntConsumer)` returning `ExchangeDirectory.ScanReport`.

- [ ] Write tests for import, acknowledgment, ignored files, malformed carriers and processed-file replay.
- [ ] Run the focused test and verify RED because `SharedExchangeDirectory` does not exist.
- [ ] Implement the scanner and select it only when All files access is active.
- [ ] Run focused tests and verify GREEN.

### Task 2: Permission and settings cleanup

**Files:**
- Modify: `android-probe/app/src/main/AndroidManifest.xml`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/SettingsActivity.java`
- Modify: `android-probe/app/src/test/java/org/reasonnotes/probe/SettingsActivityContractTest.java`

**Interfaces:**
- Produces: settings status/action for All files access; no foreground-purge pairing controls.

- [ ] Add failing contract tests for storage-access wording/actions and absence of pairing UI.
- [ ] Run focused tests and verify RED.
- [ ] Add `MANAGE_EXTERNAL_STORAGE`, special-access intent handling, and remove pairing controls.
- [ ] Run focused tests and verify GREEN.

### Task 3: Verification and candidate

**Files:**
- Modify: `PROJECT_STATUS.md`

- [ ] Run the Android JVM suite and lint/build checks appropriate to the manifest and receiver change.
- [ ] Build the debug APK, verify package/signature, copy it to persistent candidate storage, and record SHA-256.
- [ ] Update status with code/test/build evidence and remaining installation/physical acceptance.
- [ ] Commit and push the exact branch to `handoff` and `github`, verifying both remote SHAs.

