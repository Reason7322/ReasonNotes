# Forge -> Qwen Handoff

## 2026-10-24 checkpoint — simplified sync v2 Android Task 4 WIP

- Original goal: save/edit on Fedora and Android, explicit Sync transfer,
  complete two-version conflict choice, and permanent bidirectional deletion.
- Incoming completed checkpoint: Task 3 at
  `611fdc5058b1bde131821f180f6c88f412d06201`.
- Current authorized milestone: Android Task 4 v2 transport,
  acknowledgments, retryable pending state, and operation-scoped status.
- WIP implemented: carrier kinds `state-v2`/`ack-v2`, pure current-state bundle
  application, state-before-ack ordering, v1 revision read compatibility,
  retryable current-state coordinator, ack clearing, independent-note progress,
  SAF v2 scan overload, Home receive wiring, and initial SyncActivity v2 wiring.
- Focused direct-JDK verification: 44 tests passed.
- Not yet verified: Android Activity/SAF compilation, complete SyncActivity
  contracts, full JVM suite, and APK assembly. The first Gradle attempt was
  blocked before compilation because sandbox policy denied Gradle's daemon
  server socket (`java.net.SocketException: Operation not permitted`).
- Deployment/runtime: no APK build or install, no app launch, no migration of
  personal data, and no synchronization.
- Continuation: compile and repair the current Activity/SAF WIP, finish Task 4
  status tests, run the Android suite and `assembleDebug`, then record the APK
  hash. Do not start Fedora Task 5 from this checkpoint.

## 2026-10-23 Android JSON v1 launcher candidate

- Original goal: reliable bidirectional synchronization with visible progress,
  explicit failures, and success only after durable import confirmation.
- Authorized milestone: restore the accepted launcher icon on the modern
  Android JSON v1 candidate, build it, verify package/signing compatibility,
  and stop before deployment or physical synchronization.
- Incoming source: `e189d3315bb6f6e1da3441a1a814c700514620d9` on
  `forge/android-json-v1-integration`; final checkpoint SHA is reported outside
  this file because a commit cannot contain its own identity.
- Change: exact recovery icon SHA-256 `12f95baf...306e36`, plus manifest icon
  and round-icon references and a focused contract regression test.
- Focused verification: launcher contract and `TransportBundleTest` passed;
  `assembleDebug` passed. The 157-test suite was deliberately not rerun.
- APK: `/home/reason/Temporary/ReasonNotes-JSON-v1-Candidates/android/ReasonNotes-Android-JSON-v1-with-launcher.apk`,
  SHA-256 `feb2312efa2195ed739b7d434b809bc169106bfaae7287d8039a7878f832b5fa`.
- `aapt`: `org.reasonnotes.probe`, version 1 / 0.1, min SDK 26, target SDK 35,
  launcher resource packaged and referenced. `apksigner`: v2 verifies and the
  certificate SHA-256 matches the known-good recovery APK exactly:
  `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147`.
- Deployment/runtime: not installed, not launched, no ADB, no phone/data or
  recovery-checkout mutation.
- Remaining acceptance: separately authorized physical Fedora/Android JSON v1
  synchronization and durable receipt confirmation.
- One potential next action: owner-authorized installation and physical sync
  verification; it is not authorized by this checkpoint.


## Checkpoint

- Branch: `forge/android-json-v1-integration`
- Exact base: `9e2cd049282a31b1f9db45bd3ec09b6da9bc5721`
- Reference-only stale implementation:
  `71f0e3124b1f45c06fc8cf3fc9c8c4779e010066`
- Desktop protocol checkpoint:
  `88def77e2bfe3c6b2b92897b5d134395a761ffd3`
- Verify the final pushed full SHA on both `handoff` and `github`; this file
  cannot contain the hash of its own commit.

## Delta

- Added a strict canonical JSON v1 codec matching the desktop fixture exactly,
  including literal Unicode, deterministic ordering, content-addressed names,
  collision suffix acceptance, limits, and full embedded-envelope identity
  validation.
- Added JSON carrier discovery and import to the modern optimized Android scan.
  The full carrier validates before mutation; legacy `.md` imports remain.
- Repeated carriers are idempotent. A valid receipt followed by a malformed
  revision cannot produce confirmation or repository mutation.
- Every non-empty outgoing plan now creates one JSON carrier and grants one
  FileProvider URI. Existing per-envelope ledgers advance only after the share
  request is accepted.
- Removed the undeployed Android ZIP generator and its tests; no ZIP receive
  compatibility was added.
- Preserved modern Android UI, editor, startup, list, history, conflict,
  deletion, retry, and durable receipt semantics.

## Offline verification

- Clean `testDebugUnitTest`: 157/157 passed, 29 suites, zero failures/errors.
- Clean `assembleDebug`: passed.
- Clean `assembleDebugAndroidTest`: passed. The inherited instrumented exchange
  fixture was updated only to call the already-current ledger/receipt scan API.
- App APK: `android-probe/app/build/outputs/apk/debug/app-debug.apk`
  - size: 2,498,156 bytes
  - SHA-256:
    `e7cbf93bb9f3e1821d361a4b32cd50681b2c2350b12d5eac7339911e1bb9ee97`
- Test APK SHA-256:
  `eb5845c2ec9ef4eb5913a5a74695fad4068695dfbc1fba29a3289045c3b67765`

## Evidence boundary / next action

No ADB command, installation, phone-data change, or live synchronization was
performed. Physical Fedora/Android round-trip timing and durable receipt
confirmation remain the separately authorized next stage. Do not deploy this
candidate without that authorization, and do not use the protected recovery
checkout for follow-up work.
# Forge checkpoint — simplified sync v2 Task 2 RED

Original goal: save/edit on Fedora and Android, explicit Sync transfer,
two-version edit conflict choice, and permanent bidirectional deletion.

Approved design and plan:

- `docs/SIMPLIFIED_SYNC_V2_DESIGN_2026-10-24.md`
- `docs/superpowers/plans/2026-10-24-simplified-sync-v2.md`

Task 1 is complete cross-language: canonical `StateEnvelope` and
`StateAckEnvelope` plus byte-identical Java/Python fixtures. Android suite passed;
Fedora suite passed 296/296 with local-socket permission. Task 1 remote commits:
Android `ee50f1a470924d13d1c3b4f5bbb596d1f726c10f`; Fedora
`7de5e4678c671dcd6d6a8dc813b46d000f530ec7`.

Current Android WIP is Task 2 RED only: `CurrentNoteStoreTest.java` expects
durable current records, one coalesced pending state, edit/edit candidates,
permanent content deletion, deletion fences, exact ack clearing, and interrupted
pending recovery. The test fails because `CurrentNoteStore` is intentionally not
implemented. No migration test or product implementation exists yet.

No APK build/install, app launch, personal-data migration, or synchronization
occurred. Potential next action: continue Task 2 from this failing test by adding
the minimal `CurrentNoteStore`, then migration tests/implementation.

# Forge checkpoint — simplified sync v2 Task 2 complete

Task 2 now implements `CurrentNoteStore` and `LegacyRepositoryMigrator`.
Focused JDK 17/JUnit verification passes 10/10 tests: durable and coalesced
current state, exact acknowledgment clearing, two-candidate conflict choice,
content-free deletion fences, stale-carrier rejection, interrupted-pending
recovery, live/conflict/deletion migration, interrupted staging rebuild, and
safe refusal of malformed, missing-ancestry, and three-head legacy stores.

Migration tests use synthetic `TemporaryFolder` roots only. No APK was built or
installed, no app was launched, and no personal data was migrated or synced.
The previously documented Android SDK and project Gradle cache were absent;
therefore the focused tests were compiled and run directly with the retained
JDK 17 and cached Gson/JUnit dependencies. Task 3 remains unstarted.
