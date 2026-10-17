# Android ReasonNotes: plain-language sync screen

## Original observable goal and full deliverable
User wants a clean, polished Android Sync notes screen. Instead of technical counters, persistent Retry unconfirmed transfers link and Checking incoming changes... status, it should show only one user-facing progress/outcome label: Syncing… while running; a clearly positive success line when fully verified; or Error: … when it fails. Preserve the existing reliable two-way KDE Connect sync, F5 desktop receiving, durable ledger receipts, note histories, quick imports, and cross-device permanent deletion. Do not perform transfers or permanently delete real notes just to check appearance.

## Implementation
- Only one live status TextView. Idle is hidden; normal sync shows Syncing…; errors are short and do not expose internal receipt/revision IDs or exception messages.
- Phase TRANSFER_REQUESTED now displays Sync requested ✅: a KDE Connect request is not proof the computer imported it.
- Phase COMPLETE displays All selected notes synced ✅ ONLY if every selected Android-origin revision has durable confirmed ledger evidence. Previously requested but not yet confirmed revisions display Sync requested ✅. If a selected local revision remains pending or needs attention, the status is Error: Some notes need attention ❌, not an unearned success.
- No transfer counts, receipt queues, raw scan details, or always-visible retry control in the status area. Underlying coordinator and SyncStatus retain their detailed audit state unchanged.
- Ordinary Start sync behavior does NOT automatically retry older unconfirmed requests. The existing deliberate retry capability remains behind a confirmed long-press of Start sync, avoiding repeated sends by accident while keeping a recovery path.
- Full sync and selected-note sync modes remain functional; note selection/search, preview, and confirmation safeguards unchanged.

## Validation
- Offline testDebugUnitTest + assembleDebug passes; added status mapping and strict confirmation-evidence tests, including request-versus-receipt and unread/needs-attention cases, and guardrails against technical status text.
- Installer step requires existing APK signer match, before/after integrity of all private files including history and preferences, no app-data reset. Physical device screen inspection to verify layout still pending.
- If actual user data contains outstanding transfers, first success might read Sync requested ✅. This is intentionally truthful and not the final confirmation acknowledgment; entering the screen again and syncing can update the confirmation state after a legitimate receipt arrives.

## Remaining work before original end goal is complete
- Commit tested source, create and verify APK, install normally and safely with user's current approval, verify package bytes, private note preservation and Android launch. Have user confirm clean screen on phone.
