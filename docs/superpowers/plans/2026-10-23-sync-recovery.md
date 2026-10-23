# ReasonNotes Synchronization Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow ordinary Android Sync to recover a pending descendant from an exhausted local ancestor without blocking independent notes.

**Architecture:** Extend only `SyncPlanner` dependency closure. Preserve JSON v1, receipts, conflicts, purge fences, and UI structure.

**Tech Stack:** Java 17, JUnit 4, Android Gradle.

**Spec:** `docs/superpowers/specs/2026-10-23-sync-recovery-design.md`

## Global Constraints

- No protocol, UI, migration, installation, or live-device changes.
- No ledger reset or history deletion.
- Test first with synthetic repositories.

## Review Focus

- Exhausted ancestor and pending child are sent together.
- Independent pending notes still send.
- Rejected transport does not advance either ledger row.
- No ordinary resend occurs after the child becomes transfer-requested.
- Confirmed ancestry remains unchanged.

---

### Task 1: Recover complete local ancestry

**Files:**
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/SyncPlanner.java`
- Test: `android-probe/app/src/test/java/org/reasonnotes/probe/SyncPlannerTest.java`
- Test: `android-probe/app/src/test/java/org/reasonnotes/probe/SyncCoordinatorTest.java`

**Interfaces:**
- Consumes: existing `DeliveryLedger.Entry` states and `SyncPlanner.Plan`.
- Produces: a plan containing a pending descendant plus exhausted local ancestors.

- [ ] Add failing planner and coordinator regressions.
- [ ] Run focused tests and verify the blocked behavior fails.
- [ ] Permit exhausted local ancestors only as dependencies of eligible pending descendants.
- [ ] Run focused tests and complete Android sync/conflict/deletion suites.
- [ ] Update project status, commit, push, and verify both remotes.
