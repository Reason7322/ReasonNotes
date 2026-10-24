# Android Sync Correctness and Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent lost Android sync evidence and eliminate repeated full-content scans of unchanged exchange history.

**Architecture:** Use process-wide transactional locks for ledger mutations, one process-wide scan gate, and an app-private durable validated-document index. Preserve JSON v1 and strict receipt validation.

**Tech Stack:** Java 17, Android SAF/DocumentFile, JUnit 4, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-24-android-sync-correctness-performance-design.md`

## Global Constraints

- Do not delete shared exchange files or reset ledgers.
- Do not change JSON v1, receipt identities, UI design, or personal notes.
- No APK installation without separate exact-hash approval.

## Review Focus

- Two ledger instances updating concurrently preserve both updates.
- A failed receipt never becomes cached as processed.
- Same-name changed content is revalidated.
- Process restart preserves the processed-document index.
- A concurrent foreground scan cannot start a second physical scan.

---

### Task 1: Transactional delivery ledger

**Files:** Modify `android-probe/app/src/main/java/org/reasonnotes/probe/DeliveryLedger.java`; test `android-probe/app/src/test/java/org/reasonnotes/probe/DeliveryLedgerTest.java`.

- [ ] Add a deterministic failing two-instance concurrent update test.
- [ ] Run the focused test and confirm RED from a lost row/state.
- [ ] Protect complete ledger read/modify/write transactions with a canonical-path process lock.
- [ ] Run `DeliveryLedgerTest` and confirm GREEN; commit.

### Task 2: Serialized scan gate

**Files:** Modify `ExchangeDirectory.java`; test `ExchangeDirectoryTest.java`.

- [ ] Add a failing test proving overlapping callers cannot execute document processing concurrently.
- [ ] Add a process-wide scan gate around the complete scan transaction.
- [ ] Run focused tests GREEN; commit.

### Task 3: Durable validated-document index

**Files:** Create `ProcessedExchangeIndex.java`; modify `ExchangeDirectory.java`; test `ProcessedExchangeIndexTest.java` and `ExchangeDirectoryTest.java`.

- [ ] Add RED tests for unchanged skip, changed/new processing, restart persistence, and failures remaining retryable.
- [ ] Implement atomic private index persistence and integrate it only after successful validation/handling.
- [ ] Run focused tests GREEN, then the Android JVM suite and debug build; commit.

### Task 4: Status and evidence

**Files:** Modify `PROJECT_STATUS.md` and focused status tests if behavior changes.

- [ ] Record measured before/after behavior and remaining physical verification.
- [ ] Run `git diff --check`, commit, dual-push, and verify remote SHAs.
