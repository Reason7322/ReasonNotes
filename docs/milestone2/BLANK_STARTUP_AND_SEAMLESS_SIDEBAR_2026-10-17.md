# Immediate blank editor and seamless resizable sidebar — Fedora ReasonNotes

## Original observable end goal and final deliverable
The user observed that the Fedora app opens to a non-editable empty welcome screen requiring an unnecessary extra click, and a bright vertical bar (revealed wallpaper/blur gap plus line) borders the sidebar. Requested final product behavior: opening ReasonNotes always exposes a fresh blank editor with the cursor ready to type, without adding any empty persisted note/history/sync request merely by launching. The vertical bar must disappear entirely while the sidebar must remain drag-resizable.

Keep the already-proven broader ReasonNotes product: offline-first persisted notes, Markdown title/body, responsive subsecond desktop launch, F5 incoming receive, manual KDE Connect sync, permanent cross-device deletion while both apps are open, Shift-click bulk delete, correct receipts, and no lost drafts or old histories. No protected live data/installation changes without user approval.

## Implementation
- Removed EditorPane's empty-state splash in favor of always-visible existing title/editor layout. Schedule body-editor focus on component completion when no note is active. Existing note selection/title focus semantics remain.
- Presenter permits editing with no bound note ID. Ordinary typing maintains a local dirty draft, and autosave, explicit Save, switch, sync, or close materializes a note only if the draft has meaningful user-entered title/body. For body-only drafts, the generated Untitled heading is excluded from the meaningfulness test.
- Existing NoteService.create_note creates the first immutable revision just once, without an extra blank revision; the bridge refreshes note list and editor binding after note activation. An untouched editor or completely erased draft writes no revision.
- Removed SplitView's line and transparent exposed framebuffer gap. The 9px handle is now filled with the adjacent sidebar background, preserving drag geometry, color consistency, keyboard and mouse inputs and saved sidebar width.

## Validation
- 224/224 Fedora Python/Qt tests passed. Covers empty launch, actual direct keyboard typing/focus, no file before save, actual 2.5s idle timer persistence and reopening, Ctrl+S/manual autosave, title-only, generated empty Untitled, whitespace, close, switch, existing note workflows, F5, sync, and cross-device deletion.
- QML mouse simulation dragged the now-invisible handle from 300px to 355px; the sidebar resized correctly with no decorative line.
- Offscreen headless app launch succeeded without new QML binding warnings.
- No user notes/credentials or installed apps changed during development. Source worktree separate and clean after commit.
- Real monitor visual inspection and the user's live Fedora startup typing experience remain unverified until user authorizes installation.

## Safety
Do not create/merge an empty revision on launch, never overwrite existing history, never initiate note transfer by opening the app, retain old release for rollback. Check Fedora data before/after any authorized install and require exact observed startup verification.
