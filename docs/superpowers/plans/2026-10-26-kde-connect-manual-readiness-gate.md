# KDE Connect Manual Readiness Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require an explicit Android KDE Connect readiness confirmation before a user-initiated ReasonNotes transfer can begin.

**Architecture:** Add a small lifecycle-independent gate that retains the requested selection while KDE Connect is opened and permits exactly one continuation after confirmation. `SyncActivity` owns Android launch/dialog integration; the existing coordinator, carrier, and FileProvider sender remain unchanged.

**Tech Stack:** Java 17, Android Activity lifecycle, JUnit 4, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-26-kde-connect-manual-readiness-gate-design.md`

## Global Constraints

- Do not create a carrier or mutate synchronization state before confirmation.
- Cancellation and missing KDE Connect preserve all pending work.
- Manual confirmation permits a transfer attempt but is not delivery or synchronization proof.
- Preserve existing JSON carriers, note selection, conflicts, deletion behavior, and Android UI structure.

## Review Focus

- Returning from KDE Connect must prompt once, not start transfer automatically.
- Rotation/process recreation while KDE Connect is open must retain the exact selection.
- Cancel must clear only the transient readiness request.
- Missing KDE Connect must produce a useful error without invoking the coordinator.
- Repeated taps must not create overlapping readiness or sync operations.

---

### Task 1: Lifecycle-safe readiness state

**Files:**
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/KdeConnectReadinessGate.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/KdeConnectReadinessGateTest.java`

**Interfaces:**
- Produces `begin(OutgoingSelection, boolean)`, `returnedFromKdeConnect()`, `confirm()`, `cancel()`, and snapshot/restore operations.
- `confirm()` returns the exact selection and retry flag once; no other transition authorizes synchronization.

- [ ] Write tests for begin/return/confirm, cancel, exact selected-note retention, snapshot restoration, and duplicate confirmation rejection.
- [ ] Run the focused test and verify RED because the gate does not exist.
- [ ] Implement the minimal state machine.
- [ ] Run the focused test and verify GREEN.

### Task 2: Android launch and confirmation integration

**Files:**
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/KdeConnectSender.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/SyncActivity.java`
- Modify: `android-probe/app/src/test/java/org/reasonnotes/probe/SyncActivityContractTest.java`
- Modify: `android-probe/app/src/androidTest/java/org/reasonnotes/probe/KdeConnectSenderInstrumentedTest.java`

**Interfaces:**
- `KdeConnectSender.launchApplication(Activity)` checks for and opens KDE Connect without creating a carrier.
- Sync buttons call the readiness flow; only the dialog confirmation invokes the existing `startSync` method.

- [ ] Write failing contract/instrumented tests proving the launch intent targets KDE Connect and the sync entry points route through readiness.
- [ ] Run focused tests and verify RED for missing integration.
- [ ] Implement package availability handling, launch, return dialog, cancellation, and saved-instance-state restoration.
- [ ] Run focused JVM tests and the existing sender instrumentation compilation checks.
- [ ] Run the Android JVM suite and `assembleDebug`; record the APK SHA-256 without installing it.
- [ ] Update `PROJECT_STATUS.md`, commit on `forge/android-json-v1-integration`, push the same commit to `handoff` and `github`, and verify both full remote SHAs.
