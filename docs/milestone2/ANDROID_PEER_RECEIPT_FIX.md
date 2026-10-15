# Android peer identity and KDE Connect duplicate-filename fix

**Date:** 2026-10-15. **Owner-approved scope:** two narrow fixes; build and stage candidate APK; **no phone installation**, settings change, or deployment.

## Physical-test evidence and root cause

See `/home/reason/Temporary/ReasonNotes-PhysicalAcceptance/2026-10-15/PHYSICAL_SYNC_TEST_RESULTS.md` for the full physical acceptance record.

- Android imported isolated Fedora revision `d650007f6f8527b9fcd97e0eced22714411a0c9cd914cb463c3d1efa6db95b89` and queued receipt `e061527e2431656c0d73ecbf9d0b6854126a29f0e7ffbc208ea593fa04b0567f`. The pending Android receipt remained at **attempts 0**.
- Existing Android outgoing ledger records were keyed by the **physical KDE Connect device ID** `254997f28f11499d80928fe8a109749e`; generated receipt files were addressed to the **logical peer** `linux`.
- Production SyncActivity mistakenly passed the physical transport ID to SyncPlanner and SyncCoordinator as the revision/receipt peer. Consequently queued receipts were never eligible, and a valid Linux import receipt could not locate its matching delivery ledger entry.
- Linux received a valid revision as `<64-hex> (1).md`; the previous opt-in Downloads reader ignored that collision suffix.

## Changes

### Android

- SyncActivity distinguishes `target` (physical KDE Connect ID, used **only** by the share requester) from `peer = "linux"` (used by SyncPlanner, SyncCoordinator, receipt lookup and ledger history).
- DeliveryLedger provides an idempotent, atomic `migrateTargets(previousTargets, logicalPeer)` that converts previously persisted physical-ID delivery records to logical peer keys before SyncPlanner and SyncCoordinator are created.
- Migration stages **all** relevant historic targets in memory, checks conflicts between their entries and any existing logical entries, and writes one atomically replaced ledger file **only after checks pass**. Conflicting records fail closed without writing. Confirmation IDs, confirmed timestamps, retry counts, last-requested timestamps and states survive unchanged. Append-only events are not modified.
- Current configured physical target and the original pre-Task-4 default target are both eligible for migration. Safe restarts are idempotent. Non-matching records are left untouched.
- Outgoing revision and receipt selection remains independent. The existing SAF permission, Android note repository, receipt store, delivery-state location, pin state, and editor files are unchanged.
- Android UI labels now refer to **Linux** rather than **phone** as the delivery destination.

### Fedora

- In opt-in `--received-dir` mode only, KDE Connect's `<64-hex> (1).md` and `<64-hex>.receipt (1).md` filename collisions are accepted as *candidates*. Strict envelope parsing and existing revision/receipt verification remain mandatory.
- Arbitrary unrelated downloads, malformed suffixes, zero/unknown collision markers, and symlinks stay ignored.
- Received source files are read-only and are not moved/deleted. Normal inbox behavior is preserved.

## Automated verification and artifact

Executed on separate source worktree `/home/reason/Temporary/ReasonNotes-AndroidPeerFix`, branch `sol/android-peer-and-receive-collisions`, based on verified prior incoming SHA `89e50dbbda3912e74d7d1d7feeb1b5625141d61d`.

- Fedora: `PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=src QT_QPA_PLATFORM=offscreen python3 -m unittest discover -s tests -q` — **152/152 passed**.
- Android: `./gradlew --offline --no-daemon testDebugUnitTest assembleDebug` — **98/98 JUnit tests passed**, assembleDebug **BUILD SUCCESSFUL**.
- New tests cover production physical-versus-logical peer choice, preservation of retries and confirmations, idempotent migration, atomic fail-closed handling of conflicting ledger keys, selection-safe receipt transmission and Linux receipt confirmation, collision-named revision and receipt imports, and ignored unrelated Downloads.
- A byte-for-byte *copy* of the **actual received** Android file `7c1792c1475a12cb755982e69fd448871ed3d348a5a7823b8a9ef1a352ce0a83 (1).md` imported successfully into a new disposable Fedora repository, queuing its receipt. The original Downloads file was unchanged.
- Android APK: `/home/reason/Temporary/ReasonNotes-AndroidPeerFix-artifacts/ReasonNotes-Android-PeerFix-bc699070.apk`
- SHA-256: `bc6990704f138f89d13001f725ae6e2801254df32be7909e9ae5f9ed1e3951eb`, **2,448,083 bytes**.
- Package remains `org.reasonnotes.probe`, `versionCode=1`, `versionName=0.1`. Debug signer SHA-256 remains `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147` and matches the prior owner-approved installation.

## Acceptance boundaries and future test

- **No new APK installed.** The POCO's existing app, internal files, old delivery ledger and user-owned note history were not changed by this development task.
- The candidate is a debug APK from a dedicated source branch; it needs owner approval before an in-place installation.
- Before installation, re-verify the connected device identity, currently installed APK/signature, package ID and installation method. The same versionCode does not guarantee every installer will accept replacement.
- Then perform a controlled physical sync with the one already imported disposable Fedora note; inspect Android receipt ledger state and the actual KDE Connect receive files on Fedora, scan with the optional Downloads receiver, verify the exact receipt ID in the Fedora ledger, and test a corresponding Android confirmation. Do not use Sync All against live notes as a substitute for that controlled test.
- Do **not** claim real-world automatic receipt delivery is fixed until that physical retest succeeds.
- No UI theme work, cross-device pin sync, unrelated editor changes, or Task 5 expansion was undertaken.

## Worktree

All work here belongs to a separate Sol-owned worktree. Forge's Android worktree, previous Sol branches and the existing app installation were not modified.
