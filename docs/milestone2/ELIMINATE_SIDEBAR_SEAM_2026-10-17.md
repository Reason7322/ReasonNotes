# ReasonNotes Fedora: eliminate remaining vertical seam, retain resizing

## Original product end goal and requested deliverable
The user wants a responsive, native Fedora/Android note app with reliable/manual sync, F5 incoming refresh, preserved local notes and revision histories, live permanent cross-device deletions when the apps are open, Shift-click bulk permanent deletion, and comfortable typography/UI. A bright vertical seam/thin handle remains visible at the desktop sidebar edge in an Oct 17 screenshot even after attempting to recolor the SplitView handle and hide Settings/Editor scrollbars. The specific deliverable is a sidebar and editor with no separately tinted/rendered dividing strip, no unexpected scrollbar thumb, and an invisible but usable sidebar width drag target. No impact on content, synchronization, security, or native Wayland blur.

The user previously explicitly authorized immediate installation of new UI releases once validated.

## Root causes found in code
1. Main.qml created a 9px-wide, separately rendered SplitView handle. Painting it with a translucent sidebar color in glass mode can produce a distinct strip on a native transparent Wayland framebuffer; changing its color did not remove the extra composited surface.
2. Sidebar.qml still had an AsNeeded notes-list vertical ScrollBar. Prior patches disabled scrollbar indicators only in Settings and the main Markdown editor, leaving the third possible short bright thumb.
3. Simply making SplitView's handle zero-width while setting Sidebar.width during a custom drag failed: the SplitView laid out the editor at the original boundary and allowed the updated sidebar to overlap it. Tests reproduced the issue; the intermediate implementation was discarded.

## Completed approach
- Replace the SplitView layout with one fully invisible Item host containing a directly anchored Sidebar and content panel. contentPanel anchors its left edge to sidebar.right, so no separate seam rectangle, compositing layer, or gap exists.
- A twelve-pixel invisible MouseArea within unused right sidebar padding is the resize target, with a split cursor. Drag adjusts one shared liveSidebarWidth property, clamped to 250..440px and window width minus the editor's required 520px. Existing sidebar width QSettings persistence remains.
- Hide the remaining notes-list scrollbar with ScrollBar.AlwaysOff; list scrolling, mouse wheel, touchpad and normal list/selection continue working.
- No modifications to private notes, pairing keys, delivery/receipt or deletion logic, Android code or user's desktop installation until the authorized installer runs.

## Validation
- 229/229 full Fedora Python/Qt tests pass, compared with 227 baseline.
- Actual QTest pointer dragging, both directions, clamps at 250 and 440, and stable exact panel boundary with 0.001px geometric tolerance.
- Real QSettings persistence regression: resize via dragging to 362px, reopen, sidebar retains 362px, editor begins exactly at its right edge.
- A 40-note overflowing ListView reports invisible scrollbar thumb but scrolls to contentY=100.
- F5 receiving, autosave blank startup, receipt validation, sync, bulk-delete integrity and UI model regressions continue passing.
- Real glass compositor visual confirmation remains pending until authorized live install and user screenshot. Do not claim visual success until observed on monitor.

## Rollback and safety
The release installer must keep the previous version intact and compare all live app-data file hashes before and immediately after switching the launcher. Do not perform live deletion/transfer to validate appearance.
