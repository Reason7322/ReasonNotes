# Android delivery-ledger reconciliation repair (2026-10-15)

## Scope

Owner authorized repair of the exact mixed-key migration failure seen after installing `eed62719c9751886b504b3e4afd2d03a9c022a87`. Work is isolated in the Sol-owned worktree `/home/reason/Temporary/ReasonNotes-PeerLedgerMerge`, branch `sol/android-peer-ledger-merge`, based on that exact incoming SHA.

**No phone files, settings, notes, or live delivery state were modified during this repair.** No new APK was installed and no device sync was triggered.

## Observed physical blocker

The POCO's existing `org.reasonnotes.probe` delivery ledger contains two records for Android-origin revision `7c1792c1475a12cb755982e69fd448871ed3d348a5a7823b8a9ef1a352ce0a83`:

- Physical KDE Connect ID `254997f28f11499d80928fe8a109749e`: `transfer-requested`, **2 attempts**, last request `2026-10-15T12:00:27Z`.
- Logical receipt peer `linux`: `needs-attention`, **3 attempts**, last request `2026-10-12T19:11:59Z`.

The original migration rejected nonidentical rows and could prevent the private Android Sync screen from starting. The previous safe-to-migrate rules did not explain how to consolidate existing nonconfirmed state differences.

## Conservative merge policy

`DeliveryLedger.migrateTargets()` continues to stage every change in memory before making a single atomic ledger replacement. The peer identity is still logical `linux`; physical KDE Connect ID remains for transport only.

For duplicate revision/peer history:

1. **Retry attempts:** take the maximum, **not the sum** (the two representations can describe the same requests). This avoids inventing attempts or resetting exhaustion.
2. **Last request timestamp:** retain the latest observed timestamp.
3. **Unconfirmed state:** retain `needs-attention` if either row has it or the merged retry count has reached the configured maximum; otherwise keep `transfer-requested` if any attempt occurred, or `pending` if none occurred.
4. **Confirmed state:** preserve only a genuinely existing confirmed record and its exact receipt ID and confirmation timestamp. If both rows report a confirmation, their receipt IDs and confirmation timestamps must agree.
5. **Contradictory confirmations:** fail closed with no ledger rewrite, no partial migration of other revision rows, and no fabricated receipt.
6. **History:** the append-only `events.tsv` is unchanged; other delivery rows, revision envelopes, user notes, receipt files, pins, drafts, and preferences are untouched.

**Exact observed record expectation:** logical `linux` keeps `needs-attention`, attempts **3**, last requested `2026-10-15T12:00:27Z`, with no confirmation; the duplicate physical-key row is removed only when the owner later authorizes installing and running the repaired app. The state remains ineligible for automatic retry. Pending *receipt outbox entries*, which are separately keyed by `linux`, remain eligible to send when a real sync is initiated.

## Verification

Test-first: four new reconciliation tests failed against the old logic; the corrected merge makes them pass. An additional fail-closed regression covers a conflicting confirmation encountered after other transformations have been staged.

- **Android:** `./gradlew --offline --no-daemon testDebugUnitTest assembleDebug` — **102/102 passed**, 0 failures, 0 errors, 0 skipped. APK build successful.
- **Fedora:** `QT_QPA_PLATFORM=offscreen PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=src python3 -m unittest discover -s tests -q` — **152/152 passed**.
- Artifact: `/home/reason/Temporary/ReasonNotes-PeerLedgerMerge-artifacts/ReasonNotes-Android-PeerLedgerMerge-a1daeb9c.apk`
- Artifact SHA-256: `a1daeb9c54f45d9a2d9b4d6dc2c38bdd57af728e8f4ba0ab51c94fe7aa2075b9`.
- APK size: **2,345,704 bytes**.
- Package: `org.reasonnotes.probe`, versionCode **1**, versionName **0.1**, minSdk **26**, targetSdk **35**.
- Signer SHA-256: `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147` — matches the existing owner-approved Android APK signer.

## Next acceptance gate

The new APK is a **candidate only**, not installed. Owner authorization is required before another Android in-place update, migration execution, or any physical sync. Recommended precaution before installing: copy the existing phone delivery-ledger file read-only to the isolated test-evidence folder and verify its SHA; do not clear or uninstall the app.

After installation, open Android **Choose notes to sync** *without pressing Sync* and confirm the ledger's legacy rows were conservatively merged as specified, with exact count/state retention. Then explicitly authorize a controlled, preferably receipt-only sync and verify the imported Fedora test note's outstanding Android-generated receipt returns over KDE Connect. Do not claim physical receipt convergence until that succeeds.

No theme work or unrelated UI modifications are part of this repair.
