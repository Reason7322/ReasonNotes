# Round-trip receipt delivery and live Android sync completion

## Full original end goal and delivery contract
A responsive Fedora/Android ReasonNotes with secure, reliable, bidirectional KDE Connect transfer, F5 desktop incoming refresh, correct permanent local/cross-device deletion, preserved note history, fast Android incoming, and an Android Sync notes screen showing one meaningful concise status. The user observed that Android stayed forever at Sync requested while a newly synchronized note was already present in the Fedora repository. The goal is real confirmed completion, not merely a different label.

## On-device failure evidence (before patch)
- Live Android delivery.tsv contained 103 entries: 87 confirmed, 14 transfer-requested, one needs-attention and one pending.
- All 14 requested immutable revision IDs were confirmed present in Fedora's repository/history, AND Fedora had generated matching receipt envelopes for all 14.
- Fedora receipts/outbox contained 101 acknowledgments; 87 were already transfer-requested, and exactly 14 NEW receipts were still pending to send to Android.
- The desktop startup/F5 scan queued receipts, but required a second explicit desktop Sync action to actually send them.
- Android SyncActivity rendered phase TRANSFER_REQUESTED as static Sync requested and never revisited its receipt ledger or received new files while the screen stayed open.
- This is a missing receipt return trip, not a missing note or grounds for inventing local confirmations.

## Product changes
- Fedora scan_received now returns only pending durable receipt envelopes to its currently reachable paired KDE Connect phone after scanning incoming files. It never transmits ordinary note revisions, never consumes explicit note retries, never retries previously requested receipts automatically, and skips outgoing calls under headless --check-launch validation.
- The desktop receipt sender validates registered receipt paths through ReceiptStore, verifies the paired device is reachable, and advances receipt delivery state only after KDE Connect accepts each transfer request. Unreachable peers keep receipts pending.
- Android SyncActivity freezes the exact set of revision IDs newly requested by the user's current operation, and polls its existing strict exchange/receipt scanner while the SyncActivity remains in foreground. Only canonical persisted receipt evidence that transitions every requested revision to confirmed can produce the success label.
- The waiting text is neutral, not a check-mark success. If confirmation arrives, Android shows All selected notes synced ✅ when the complete selected history is confirmed; if there are other older unconfirmed revisions outside this operation it shows Sent notes synced ✅ instead. No false claim that all historical transfers completed.
- If the computer sends no confirmation for two minutes, the Android screen reports Error: Computer confirmation not received ❌, not an invented failure of the note transfer. It stops polling on pause/destroy and never resends notes just to check status.
- Rotation/restoration safeguards, in-flight I/O serialization, peer/receipt validation, idempotent re-scan, and live permanent deletion fences remain enforced.

## Validation and remaining work
- Android testDebugUnitTest assembleDebug passes 148/148. New tests cover only current transfer attempts tracking, mixed/partial/outdated receipt evidence and no false green status.
- Fedora 231/231 Python/Qt tests pass, including isolated receipt-return tests for first request, no silent retries, unreachable and failed send.
- Next: commit, install in place on both devices preserving hashes of all private live note data and pairing settings; run a real 14-receipt return test with the existing pending receipts; observe Android ledger transitions to confirmed without manually triggering a new note transfer. Check no note or purge state changes and no wrong-device receipt is accepted. The full product acceptance remains INCOMPLETE until that live roundtrip is observed.
