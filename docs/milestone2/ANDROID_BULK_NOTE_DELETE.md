# Android home-screen bulk note deletion — 2026-10-16

## User-observable deliverable

On the Android home screen, long-press a note card to enter selection mode. The held note gains a gold selection outline and checkmark. Tap more cards (including both pinned and other notes) to select/deselect them without opening an editor. The selection bar reports the count and offers Delete, Cancel and an overflow menu for Pin selected, Unpin selected and Select all visible. Back cancels selection. Single tap outside selection mode still opens the note. The existing pin action remains available through the selection bar rather than the previous long-press popup.

Delete presents a confirmation with the exact selected count. A background operation checks every selected note for a single, live head *before deleting any*, aborting the entire operation if a note is already deleted, missing or conflicted. Each successful deletion creates a normal recoverable immutable tombstone via `NoteService.deleteNote`, eligible for ordinary synchronization; no automatic network send occurs. Unexpected mid-operation failures are reported accurately, with failed notes remaining selected. A confirmed operation is allowed to finish across Android activity recreation; no uninstall or data clear is part of this deliverable.

This worktree incorporates the Android receive-on-resume code and tests from commit `4f7fd49` in addition to the latest Fedora fixes at `0a25e67`, so the Android APK retains that newer incoming-transfer behavior.

## Build / tests

Project-local Java 17, Android SDK 35 and Gradle 8.9, fully offline:

`./gradlew --offline --no-daemon testDebugUnitTest assembleDebug`

Result: **120/120 Android unit tests pass**; debug APK assembly succeeds. New tests validate selection toggling, select-all and snapshots; multiple independent deletion tombstones; persisted history; full-batch rejection for conflicts, deleted or missing notes; empty selection. Java compilation verifies the UI wiring but actual touch feedback, button layout and long-press gesture still require a physical phone check.

Package ID: `org.reasonnotes.probe`; signer SHA-256: `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147`, matching previously approved debug-signed APKs. No phone was connected by ADB during verification. **Candidate only: no installation performed.**

## Physical acceptance steps

1. Take a verified backup of phone private app data and confirm the currently installed signer before approving a replacement installation; update in place without clearing app storage.
2. Launch Android ReasonNotes and long-press a card; it should gain a checkmark. Tap a second card, confirm the count reaches two, then unselect one. Try Cancel and Back independently.
3. Select cards from Pinned and Others, use Select all visible, and test Pin selected/Unpin selected; ensure search and editor still operate normally after exit.
4. Confirm deletion of two disposable notes. Check that both leave the home list, unselected notes remain, and immutable tombstones persist and become eligible for the existing explicit sync flow.
5. Create a synthetic conflict and select it with a normal note; deletion must refuse the entire batch and leave notes intact.
6. Verify the Android incoming receive-on-resume path and the PC client are unchanged.
