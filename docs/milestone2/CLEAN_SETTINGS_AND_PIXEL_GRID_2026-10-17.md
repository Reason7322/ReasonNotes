# Fedora ReasonNotes — clean Settings, no bright scrollbar, pixel-aligned sidebar

## Original observable end goal
The user wants an uncluttered, responsive Fedora/Android notes product with instant startup-ready editing, reliable manual KDE Connect note sync/F5 incoming receipt scanning, permanent automatic cross-device deletion while both apps are open, Shift-click bulk deletion, preserved note history, and no confusing visual artifacts. The requested deliverable here is a Fedora UI patch for three specific screenshot defects: remove two redundant Settings footer captions; remove the remaining small bright vertical strip at the top (consistent with Qt scrollbar thumb, not the already-fixed full-height SplitView divider); align New Note, Save, Sync, Delete, Search, and note row icons and text pixel-perfectly.

The user explicitly authorized installing the next version as soon as it is ready; no second installation question needed.

## Implemented
- Removed visible Settings labels 'Settings are saved automatically.' and 'Your notes stay in their original language.' without changing preferences, language controls or any note content.
- Disabled only the visible Qt ScrollBar indicators for Settings ScrollView and long-note Markdown editor ScrollView; mouse wheel/touchpad scroll and programmatic flickable scroll remain available. Kept the 9px sidebar resizing handle, the sidebar scrollable note list and other app behavior unchanged.
- Gave New Note a 24px icon slot shared with other sidebar buttons and notes; normalized its title x-offset from 61 to 64 in the 300px reference sidebar.
- Aligned the search icon slot from x38 to x28, with fixed 24px width, and the search input text from x66 to x64. Other action icons and note icons already start at x28 and labels at x64.
- Preserved button widths/background edges at x24 and full keyboard/mouse functionality.

## Validation and remaining acceptance
- All 227 Fedora Python/Qt regression tests pass (baseline 224 + three new tests). Pixel-geometry test measures each sidebar action and note row icon at x28, text at x64, outer surfaces at x24 in actual offscreen QML, with 0.01px tolerance.
- Settings screenshot captions absent and Settings vertical scroll indicator invisible at 560px high. Long note (200 lines) vertical indicator invisible while contentY is scrollable to 150px. No new QML errors.
- Existing blank-first-note behavior, resize handle drag, F5, sync, bulk deletion, history and delivery checks preserved in full suite.
- User's real-monitor visual confirmation is still needed after authorized installation; no unrequested changes to Android, credentials, note files, history, or exchange folders.
