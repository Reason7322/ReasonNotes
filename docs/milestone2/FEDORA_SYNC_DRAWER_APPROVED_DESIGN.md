# APPROVED desktop sync interface — right-side drawer
Date: 2026-10-16
Status: **Selected by the owner**, not an open design exploration.

## Approved visual reference
The user selected the **fourth mockup**, showing a full-height right-side panel over the note editor, specifically the image referenced in conversation as `dark_note_app_sync_drawer.png` (displayed by the user at the selection turn). This file is a **visual reference only**, not a source of mock data, real project content, or obsolete labels. The selected mockup predates earlier desktop polish; preserve current live app theme/language, current sidebar alignment, and removal of stray "Markdown" and shortcut labels.

## Implementation contract
- Replace the centered tall modal with an **edge-attached right drawer** (~520–600 px wide on a 1440–1650 px window), full usable application height with a subtle shadow/overlay. At narrower widths stay inside window.
- Header: title, concise subtitle, top-right close; responsive to English and Polish.
- **Two compact segmented modes**: All notes / Selected notes. Selected mode exposes search, Select all/Clear, real selected count, and vertically scrolling catalogue. All mode replaces list with a brief explanatory summary.
- Compact note rows with title, sanitized single-line plain-text preview, muted request-state chip and accessible right-aligned checkbox. No markdown heading leakage or redundant status appended to preview. Tombstones stay selectable and identifiable as such.
- Consistent 8/12/16/24 px spacing, flat subtle background and borders; no giant radio cards or stacks of outlined form rectangles.
- Sticky footer with concise receipt-confirmation notice and Cancel/Start Sync. Only the catalogue scrolls.
- The visual reference uses **fabricated extra notes** (Meeting Notes, Ideas, Shopping List, Work Log); do **not** create or display them. Source list exclusively from existing `QtNotePresenter.syncModel`.
- Respect existing **Dark/Light** and **English/Polski** settings.
- Preserve semantics: backend selection set, search, select-all/clear, selected vs full scope, status truthfulness, pending/transfer requested, receipt confirmations and no automatic transfer. Merely opening the drawer has no outbound network side effects. Keep start button disabled with zero selected notes in Selected mode; support Escape, cancel and close.
- Do not rewrite the transport, note repository, notes data, or Android app; do not select/sync anything on behalf of the user.

## Acceptance
**Implementation completed in this checkpoint.** The existing SyncDialog.qml was redesigned as a right-aligned edge drawer without altering sync or repository implementation. Real QML tests verify 1648×928 and minimum 820×560 layouts, selected/all modes, search filtering, actual cleaned preview rendering, Select all/Clear behavior, Start Sync enablement, dark/light and Polish labels, and no outgoing transfer triggered. Full Fedora suite: **178/178 tests passed**. A 1440×900 Qt Quick render of synthetic acceptance notes is stored in `acceptance/visual/sync_drawer_preview.png` (outside Git). Native Wayland launch and live-note preservation are checked at installation time. Never terminate an unsaved user window.
