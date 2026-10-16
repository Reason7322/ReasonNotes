# ReasonNotes cross-device permanent deletion — 2026-10-16

## Original product goal and non-negotiable acceptance criteria

The user wants permanently deleting a note on **either Fedora or Android** to immediately remove it and every prior revision from **that device**. Other-device deletion should happen **automatically once both apps are open on the same paired network**. Do not run always-on background services or require manual Sync taps to propagate deletions. An old transfer or stale draft must never resurrect a purged note. Preserve the prior selection UX: bulk selection deletes all eligible notes but **skips and reports unresolved conflicts**. Preserve normal note-content sync, editable notes, UI responsiveness, Liquid Glass, offline operation and existing repository safety. No live installation or user-data manipulation without specific authorization.

The software can erase the application-managed note history and its own drafts, derived working views, pending transfer/receipt metadata. It cannot guarantee SSD-level secure overwrite, removal from previous backup archives, separately downloaded KDE Connect files or other external copies; those require a separate protected, user-approved cleanup action.

## Implementation

- A durable, content-free per-note fence is written first to `purged/<UUID>.id` and fsynced. Application-managed history, generated views, editing drafts, pending deliveries and receipt records for that note are then removed. Interrupted cleanup resumes on repository reconciliation; repositories hide fenced notes immediately and reject stale incoming original revisions without re-saving them. Existing note IDs remain unavailable for recreation; a new note receives a new ID.
- Android bulk deletion stages all eligible fences then performs one shared delivery/receipt metadata cleanup, and reconciles once. Existing bulk conflict-skipping behavior is retained. Single-note explicit Delete is permanent, with irreversible confirmation. Editor recovery drafts refuse writes after the fence and use the same repository lock as deletion.
- Fedora `NoteService.delete_note` now purges instead of creating a recoverable deleted revision. Presenter no longer saves an uncommitted draft *just before deletion*. QML delete confirmation states irreversibility, and removed notes disappear from the sync catalogue.
- A lightweight authenticated foreground-only LAN channel uses a 256-bit private pairing key, HMAC-SHA256 request AND response signatures, short-lived timestamp, nonce replay prevention, payload size limits, and canonical note IDs only. **No note bodies or draft text travel over this channel.** Port 49233 TCP uses a normal user-process listener on Fedora ONLY while ReasonNotes runs. The Android application polls every 5 seconds only while at least one of its activities is started; local deletion also requests an immediate foreground poll. Both devices merge and persist a union of purge IDs. Network downtime leaves local deletions durable until both apps run again.
- Fedora Settings displays the LAN IPv4 and private pairing key for manual setup. Android Settings → Connection → Automatic deletions accepts that pair. No new always-on Android service, desktop daemon or startup task. Normal content synchronization continues using the old explicit KDE Connect flow.
- Fedora source reads/writes share a repository lock so deletion does not race ordinary UI saves. Android's existing repository-wide lock is reused for draft/purge consistency. Remote deletions refresh the open home list and close an affected open editor.
- A system-state check found Fedora Workstation firewalld already allows port 49233 TCP under its existing 1025–65535/tcp allowance. No network or firewall settings were modified.

## Verified automation

- Fedora Python/Qt unit/smoke suite: **202/202 passed** (at last full run), including anonymous HMAC local server tests, replay, invalid signature/payload, authenticated response, stale import rejection, crash-after-fence repair, purge conflict/history tests, and existing desktop/UI regressions.
- Android offline Gradle `testDebugUnitTest assembleDebug`: **131/131 passed** (at last full run), including cross-language deterministic HMAC signature, LAN restrictions, a 15-note batch with one conflicted note skipped, stale draft write rejection, immutable history purge and repository-open recovery. The synthetic 15-note purge benchmark executes within milliseconds on Fedora JVM, **NOT** a measurement on the POCO.
- Fedora `--check-launch` offscreen launched successfully in a new empty temporary repository, without creating a pairing key or binding a live listener.
- Debug APK uses the existing `org.reasonnotes.probe` package and signing certificate. No live installation occurred, and the currently installed apps remain untouched. ADB phone is presently disconnected.

## Remaining completion / acceptance actions

1. Commit and package this **paired** Android + Fedora candidate, keeping clean worktree, prior releases and rollback.
2. Obtain explicit user permission before installing Fedora and Android replacements. Normally take a verified data backup first for safety, without clearing app data; **previous backups contain old note contents** and their retention/cleanup must be addressed with the user because deletion is irreversible and user dislikes backups. Do not delete any old backup or Downloads files without separate approval.
3. Set up manual pairing in both apps; verify credentials are never sent over the LAN, only HMAC headers and note IDs. Test on two **disposable** notes, one deleted from Android and one from Fedora, with both apps open. Assert full history purge on both, live UI refresh, zero stale draft/receipt content, no manual Sync tap, and stale old revision imports blocked.
4. Test one device closed while the other deletes. Confirm immediate local deletion, delayed automatic propagation when apps reopen and device reconnects; confirm zero persistent service when app closed.
5. Measure actual POCO deletion and sync picker load performance; user previously observed 10-second operations. Distinguish synthetic JVM timings from user-perceived startup/latency.
6. Do not claim deletion of old external backups/KDE Connect copies or forensic SSD erase. The product remains **INCOMPLETE until physical pair testing and authorized installation are accepted**, even though isolated code/build tests are passing.
