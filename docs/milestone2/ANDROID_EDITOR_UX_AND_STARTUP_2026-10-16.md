# Android ReasonNotes editor UX and startup usability — 2026-10-16

## User's observable end goal

Android ReasonNotes must open and become usable quickly, and note creation/editing must feel like a clean notepad instead of a developer prototype. The screenshot shows misaligned title and body content, an unhelpful 'Edit note' label, 'Write Markdown…' placeholder, permanent save and delivery-status labels above the writing area, and oversized boxed back/menu controls. Those elements must go. Preserve real autosave and synchronization safety, conflict resolution, keyboard usability, private app data, and the existing bulk-delete behavior (delete every non-conflicted selection, skip conflicts, keep tombstones). Aim for the previously requested roughly one-second deletion and rapid note startup, but do not claim physical responsiveness without verifying it.

## Implementation in this checkpoint

* `EditorActivity` is now a flat dark writing surface with title and body aligned to the same left padding; the body has no background card/border. The title is editable; the body uses the neutral hint 'Start writing…'.
* The redundant 'Edit note' heading is gone. The toolbar has a left-chevron vector icon and a three-dot vector icon with 48dp touch targets and a transparent/ripple background. Button accessibility descriptions remain, and the actions retain Back/Save/Delete/Sync details.
* Removed permanently visible 'Saved locally' and hard-coded 'Phone delivery: not confirmed' labels from the editor. Routine auto-save and draft safety remain intact; a nonpersistent warning is displayed for actual save failure, stale draft, or repository error. The status should not disappear simply because a stale draft receives another keystroke.
* `EditorActivity` builds the UI immediately. Repository initialization, note load, and generated-view reconciliation are moved to its background executor. Inputs are disabled until committed content and any recoverable draft have been loaded to avoid overwriting user edits. Empty notes focus the body and request the keyboard after load; nonempty notes don't force-open it. No repository revision queries in the overflow popup's UI thread.
* `ProbeRepository.openForDisplay` can read immutable revision history and ensure directories without synchronously reconciling the derived working-view files. `MainActivity` now uses that path for the initial card list and schedules a full repair on the I/O worker after the cards appear. The editor also repairs views after becoming interactive. Conflicted notes and sync screens still have their existing explicit reconciliation routes. Missing working files remain recoverable from immutable history.
* Existing Android receive-on-resume, bulk deletion, and all earlier synchronization integrity checks were retained. No new live note mutation, network transfer or phone installation was performed.

## Validation performed

* Android Gradle offline `testDebugUnitTest assembleDebug`: **126/126 passed**, APK assembled.
* Fedora Python/Qt suite: **194/194 passed**.
* New repository test: opening via the display-only path reads actual committed history without rewriting missing derived files, while subsequent reconciliation repairs the files.
* UI correctness is not fully established by JVM tests: still requires real-phone screenshot and touch verification, especially navigation icons, title/body alignment, keyboard resizing, draft restoration, failed-save warning and performance.

## Remaining work / acceptance

1. With separate user authorization, take a fresh verified phone-private-data backup, install the signed APK in place, verify signing/hashes/data.
2. Check launching the Android app until existing notes are visible and tappable; compare with prior 10-second delay.
3. Create a disposable note, verify it opens straight into an aligned editable title/body, without the removed labels or boxed toolbar buttons. Verify focus/keyboard and autosave after leaving/reopening.
4. Verify a stale or failed save remains visibly recoverable and a conflicted note is not silently overwritten.
5. Recheck deletion latency with several disposable notes; the phone's real measured speed is still unverified. Do not present JVM benchmark as a physical-speed guarantee.
