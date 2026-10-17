# Android conflict-screen permanent deletion

## Original observable user goal
The user explicitly requested complete permanent removal of the Android conflict note titled Concurrent third head, including all past revisions and its related Fedora copy, not merely hiding the card. Preserve all unrelated notes, histories, KDE Connect synchronization, receipt validation, fast incoming display, F5 desktop behavior, and automatic cross-device deletion.

The exact target UUID was verified as ffffffff-aaaa-4aaa-8aaa-ffffffffffff on both devices. Android holds 5 target revision files; Fedora holds 2. A durable purge fence is needed on both sides.

## Protected-action status
A temporary one-shot Android instrumentation helper was refused by Android with INSTALL_FAILED_USER_RESTRICTED: Install canceled by user. The helper APK was not installed; subsequent checks found no target purge marker, and both copies still retain their history. Do not bypass or retry that canceled installation. Re-establish consent before another normal app installation.

## Implemented product action
- ConflictActivity provides an explicit Permanently delete every version action, distinct from keeping a revision or bulk deleting safe notes.
- A confirmation dialog warns about all irreversible revisions and history being erased, including paired-device propagation when connected.
- The note head IDs are frozen at confirmation. The repository atomically validates the same exact conflicting heads under the repository lock before committing the durable fence. Stale selections fail closed, preserving the note and returning the user to inspection.
- Existing purgeNoteLocked writes the fence BEFORE erasing history, generated state, delivery/receipt references and drafts. The fence prevents resurrection after interruption.
- Only afterward does it clear the deleted note's local pin; it nudges the foreground purge worker. Home removes the card on return.
- Ordinary bulk deletion remains unchanged and continues to skip conflicted notes.

## Validation
- Android offline testDebugUnitTest assembleDebug: 143/143 passing. Regression checks conflict deletion on an isolated repository while unrelated revision bytes remain identical, and stale-head rejection without any purge marker.
- APK SHA256 a572bb493bf9ee8030b3c55d7f9b103aec918924349e907b99d8857d747757ef, signer unchanged.
- No new app installed and no real note deletion occurred. Original end goal remains INCOMPLETE.

## Exact remaining work
1. Ask approval for an ordinary in-place Android application update following canceled test-runner installation. Check package signer, preserve app data, launch and verify history.
2. User opens exact Concurrent third head note, taps Permanently delete every version and confirms it. Verify Android fence exists, target history is absent, and all other notes survive.
3. When Fedora is open and paired, verify existing foreground purge propagation removes the target history on Fedora with a durable fence. If propagation fails, diagnose safely using the authorized repo API, not manual file unlinking.
4. Verify no stale transfers resurrect the target and all remaining notes/history are intact. Prior historical backups are separate from live repositories.
