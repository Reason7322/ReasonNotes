# Coalesced Autosave Ancestry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the latest local autosave synchronize as a descendant of the peer's older known state without weakening genuine conflict protection.

**Architecture:** Android and Fedora current-state stores will preserve the original parent set when replacing an unsynchronized pending state, so repeated autosaves remain one bounded child of the last synchronized base. Valid live states that cannot otherwise be ordered will be retained as explicit conflicts instead of returning `needs-attention`.

**Tech Stack:** Java 17/JUnit 4/Gradle on Android; Python 3/unittest on Fedora.

**Spec:** `docs/superpowers/specs/2026-10-25-coalesced-autosave-ancestry-design.md`

## Global Constraints

- Preserve state-v2 carrier and acknowledgment formats.
- Do not add history storage or transmit superseded autosaves.
- Preserve complete versions for genuine conflicts.
- Do not mutate physical-test or production repositories during development.
- Do not install or deploy rebuilt artifacts without exact-hash authorization.

## Review Focus

- Three sequential local saves must converge when the peer still holds the original state, without parent-list growth.
- Two independent edits from the same original state must remain a conflict.
- A deletion after multiple local saves must remain recognizable as a descendant.
- Pending state must remain pending until a matching durable acknowledgment arrives.
- Malformed envelopes and deletion-fenced states must continue to fail closed.

---

### Task 1: Android ancestry closure

**Files:**
- Modify: `android-probe/app/src/test/java/org/reasonnotes/probe/CurrentNoteStoreTest.java`
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/CurrentNoteStore.java`

**Interfaces:**
- Produces: bounded local `StateEnvelope` parents inherited from an existing pending state, plus explicit conflict storage for valid unorderable live states.

- [ ] Add a regression test that imports A, saves B and C, then proves a peer holding A applies C rather than returning `needs-attention`.
- [ ] Add assertions that independent edits from A still conflict, deletion after B/C retains only A as its base, and parent count does not grow.
- [ ] Add a regression proving valid unknown ancestry becomes an explicit conflict while malformed/deletion-fenced input still fails closed.
- [ ] Run the focused test and verify the sequential-save assertion fails with current code.
- [ ] Implement one bounded parent-selection helper and use it for local upserts and deletions; replace valid-live `needs-attention` with durable conflict storage.
- [ ] Run the focused test and Android JVM suite; verify PASS.
- [ ] Commit the Android task on `forge/android-json-v1-integration`.

### Task 2: Fedora ancestry closure

**Files:**
- Modify: `tests/test_current_store.py`
- Modify: `src/reasonnotes_sync/current_store.py`

**Interfaces:**
- Consumes: the same parent-closure semantics as Task 1.
- Produces: byte-compatible Fedora bounded-coalescing and conflict-recovery behavior.

- [ ] Add the matching A → B → C peer-apply regression, bounded deletion ancestry, and valid-unknown-ancestry conflict assertion.
- [ ] Preserve and run the existing independent-edit conflict test.
- [ ] Run the focused test and verify the sequential-save assertion fails with `needs-attention`.
- [ ] Implement the same bounded parent selection and valid-live conflict fallback on Fedora.
- [ ] Run the focused test and Fedora suite; verify PASS.
- [ ] Commit the Fedora task on `forge/reasonnotes-completion`.

### Task 3: Cross-platform verification and candidates

**Files:**
- Modify: both repositories' `PROJECT_STATUS.md`
- Modify: both repositories' `handoffs/FORGE_TO_QWEN.md`

**Interfaces:**
- Consumes: Tasks 1 and 2 ancestry semantics.
- Produces: independently identifiable Android and Fedora candidates for resumed physical acceptance.

- [ ] Add or update a focused cross-platform fixture/test proving Fedora A accepts Android C with skipped intermediate B and still conflicts on independent siblings.
- [ ] Build both candidates and verify artifact identities.
- [ ] Record that physical acceptance remains pending and preserve the existing failed carrier/test state.
- [ ] Push each repository's exact checkpoint to `handoff` and `github`; verify both full remote SHAs match local HEAD.
- [ ] Stop before Android installation or further physical synchronization.
