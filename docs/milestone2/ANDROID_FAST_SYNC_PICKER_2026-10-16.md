# Android fast sync picker — 2026-10-16

## User end goal and acceptance criteria

1. Settings → Choose notes should populate promptly, not wait 10 seconds at Loading notes. Actual phone timing remains UNVERIFIED until tested on-device.
2. User also requested: if I delete a note, just delete it, I don't need backups. The exact semantics are UNRESOLVED: permanent erasure of every revision across devices vs ordinary deletion without extra backup copies. Existing implementation writes immutable tombstone history, not a fresh backup during deletion. No silent change to hard purge is permitted until the user clarifies this.
3. Preserve sync selection, delivery evidence and retries, conflicted history, recoverability, prior Android editor/settings, bulk deletion and Fedora desktop compatibility.
4. Build signed artifact for approved in-place installation; no installation without separate approval.

## Diagnosis

The former SyncPlanner catalogue loaded revision history once and reloaded it again via repository.noteIds and per-note repository.heads. It also reread the entire delivery ledger for each revision and each delivery status. Missing local ledger registrations were committed individually, with a separate file sync per new entry. SyncActivity performed repository-wide working-view reconciliation before the picker could populate.

## Fix

- Catalogue now loads revision history once, groups notes in memory, evaluates one graph and resolves all delivery states from one ledger snapshot. Deleted unconfirmed tombstones are still included for outgoing deletion synchronization.
- DeliveryLedger.registerLocals validates all new identities before changes, preserves confirmed/requested/attention states, reads the existing ledger once, and writes at most once if new pending records are needed. Repeated registration does not write.
- Normal outgoing plan registration uses the batch registration path. SyncActivity opens a history-only repository for viewing and defers full recovery reconciliation until the user starts an actual sync in the background.
- No note content or Android phone installation changed in this candidate.

## Verification

- Android offline Gradle testDebugUnitTest assembleDebug: 128 passing JVM tests, APK built.
- Added large fixture of 18 notes and 126 revisions with status assertions; catalogue took 5 ms on the Fedora host JVM, NOT a measured POCO cold-load time.
- Added durable batch ledger test to confirm duplicates do not rewrite files, invalid batches are rejected, and confirmed entries retain their receipt identity.
- Fedora Python/Qt tests: 194 passed.
- Pending: copy and verify signed APK, obtain installation authorization, preserve existing phone data through backup and in-place update, then confirm responsiveness on real phone.
- Deletion permanent/soft behavior remains pending user clarification and must not be substituted by a storage implementation detail.
