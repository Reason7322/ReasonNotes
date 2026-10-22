# Android JSON Transport Bundle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send and receive every non-empty Android sync plan as one canonical JSON v1 KDE Connect payload.

**Architecture:** A pure Java codec owns deterministic creation and strict validation. ExchangeDirectory validates an entire carrier before applying existing envelope import operations; SyncActivity shares one FileProvider URI.

**Tech Stack:** Java 17, Android API 35, JUnit 4, Android FileProvider/KDE Connect share activity.

**Spec:** `docs/superpowers/specs/2026-10-22-json-transport-bundle-design.md`

## Global Constraints

- Keep revision, receipt, history, conflict, deletion, and retry semantics unchanged.
- Preserve legacy individual `.md` imports; do not add ZIP support.
- Validate the complete JSON carrier before repository mutation.
- Keep UI layout and presentation unchanged.

## Review Focus

- Java canonical bytes must match the literal Python fixture including Unicode and escapes.
- Malformed UTF-8 and non-canonical JSON must be rejected.
- Duplicate/oversized/mismatched members must mutate nothing.
- A one-item plan must share one JSON URI, not its source `.md`.
- Rejected sharing must leave every included ledger entry eligible.

---

### Task 1: Pure Java canonical bundle codec

**Files:**
- Create: `android-probe/app/src/main/java/org/reasonnotes/probe/TransportBundle.java`
- Create: `android-probe/app/src/test/java/org/reasonnotes/probe/TransportBundleTest.java`
- Modify: `android-probe/app/build.gradle.kts` (cached Gson 2.10.1 for the same parser on JVM and Android)

**Interfaces:**
- Produces: `TransportBundle.create(List<Item>)`, `TransportBundle.parse(byte[], String)`, and immutable carrier/items.

- [ ] Write failing tests for the shared literal fixture, Unicode escaping, schema/order, limits, duplicates, malformed UTF-8, and identity mismatch.
- [ ] Run the focused JUnit test and verify RED.
- [ ] Implement the minimal deterministic codec and parser.
- [ ] Re-run focused tests; expect PASS.
- [ ] Commit the task.

### Task 2: Atomic Android JSON receive

**Files:**
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/ExchangeDirectory.java`
- Modify: `android-probe/app/src/test/java/org/reasonnotes/probe/ExchangeDirectoryTest.java`

**Interfaces:**
- Consumes: Task 1 validated carrier/items.
- Produces: carrier application through existing revision/receipt paths only after full validation.

- [ ] Write failing tests for mixed carrier import and malformed all-or-nothing behavior while retaining `.md` classification.
- [ ] Run focused tests and verify RED.
- [ ] Add `.json` discovery, validation, and existing-envelope dispatch.
- [ ] Re-run focused tests; expect PASS.
- [ ] Commit the task.

### Task 3: One-carrier Android send and candidate build

**Files:**
- Modify: `android-probe/app/src/main/java/org/reasonnotes/probe/SyncActivity.java`
- Modify: `android-probe/app/src/main/res/xml/file_paths.xml`
- Modify: `android-probe/app/src/test/java/org/reasonnotes/probe/SyncCoordinatorTest.java`
- Modify: `android-probe/app/src/androidTest/java/org/reasonnotes/probe/KdeConnectSenderInstrumentedTest.java`

**Interfaces:**
- Consumes: Task 1 carrier creation.
- Produces: one FileProvider URI and one KDE Connect share request per non-empty sync.

- [ ] Write failing tests for one carrier containing mixed planned envelopes and rejection preserving per-envelope eligibility.
- [ ] Run focused tests and verify RED.
- [ ] Build the carrier under app-private transport outbox and share its single URI.
- [ ] Run the full Android unit suite and assemble the debug APK.
- [ ] Commit and dual-push the Android checkpoint.

### Task 4: Physical candidate round trip

**Files:**
- Modify: candidate validation record only after observation.

**Interfaces:**
- Consumes: exact desktop and Android candidate artifacts.
- Produces: measured Android-to-desktop and desktop-to-Android payload counts, elapsed times, durable confirmations, and failure evidence.

- [ ] Install only the candidate artifacts through the authorized acceptance path.
- [ ] Transfer a disposable multi-revision fixture Android to desktop and record payload count/time through durable receipt import.
- [ ] Transfer a disposable multi-revision fixture desktop to Android and record payload count/time through durable receipt import.
- [ ] Exercise unreachable/disconnected error behavior.
- [ ] Record exact SHAs/hashes and leave production untouched.
