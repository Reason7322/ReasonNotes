# Receipt reconfirmation collision — 2026-10-16

## Original user goal and acceptance

The user's phone can sync an individual ReasonNotes note to the PC without being blocked by old valid receipts. A previously confirmed revision must remain confirmed by its original receipt. Different peer, different revision, different note, never-requested and malformed receipts must still be rejected. No outgoing transfer is counted as delivered before a proper confirmation. Preserve new permanent deletion behavior, Android fast sync picker, editor/settings UX, existing notes/history, and Fedora app behavior.

## Reproduced exact case (read-only inspection)

POCO's saved exchange folder had two independently canonical acknowledgments from linux to android for the same revision of the same note. Earlier receipt imported-utc=2026-10-15T14:55:19Z was confirmed and persisted in phone's ledger/inbox. Later receipt imported-utc=2026-10-15T16:33:11Z was still present in the exchange folder. Receipt IDs differ because imported time is part of the canonical SHA256 identity. Old ReceiptStore.validateConfirmation rejected any differently hashed receipt after ledger confirmation; ExchangeDirectory.scan therefore blocked outgoing sync with the error "confirmed revision has a different receipt" on each scan. The presence of a second canonical receipt is not evidence of incorrect note content.

## Fix on both Android and Fedora

The high-level receipt scanner parses and validates the new receipt as before: canonical envelope identity/hash, matching destination, known local revision and note, previously requested delivery, matching target peer. If the delivery ledger is already confirmed for that exact revision/peer, with a different valid receipt ID, that later receipt is treated as an already-satisfied, redundant acknowledgment. The original ledger receipt/confirmation timestamp/event remain untouched; no second receipt gets written to the private inbox and no new confirmation is counted. Startup reconciliation safely skips previously persisted redundant receipts too. Direct low-level DeliveryLedger.recordConfirmed remains strict on mismatches.

Normal new confirmations are still persisted before the ledger changes. The fix does not silently approve an unknown revision, cross-peer receipt, modified note identity, unrequested transfer, or malformed body.

## Automated tests and remaining work

- 131/131 Android JVM tests pass, offline testDebugUnitTest assembleDebug produced signed APK.
- 202/202 Fedora Python/Qt tests pass, including receipt recovery and previous desktop behavior.
- Regression tests check duplicate imported timestamps, original receipt identity unchanged, no extra confirmed event, no extra inbox file, old redundant stored receipt skips recovery, invalid identities still rejected.
- No existing user data, exchange files, installed APKs, or live Fedora release were changed during diagnosis or development.
- New Android/Fedora candidate must be installed only with user approval. Then exercise real phone sync and verify no blocking error; delivery remains unconfirmed until receiving peer returns a receipt.
