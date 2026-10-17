# Liquid Glass readability over bright wallpapers, especially floating

## User-visible end goal
ReasonNotes' Fedora Liquid Glass appearance must remain readable as a floating window over bright, detailed wallpapers, comparable to the denser frosted surfaces of the user's Dolphin window. Preserve the glass theme, blurred compositor surface, live Kvantum/Noctalia colors, rounded edges and desktop app workflows. Keep Dark and Light themes identical, and do not modify Android, user's notes/history, sync, permanent deletion, or Hyprland user settings.

## Observed underlying defect
In the existing release Theme.qml uses opacity 0.82 for the main text pane, 0.84 for the sidebar, and 0.89 for control surfaces when Wayland compositor blur is available. That transmits ~18% of a detailed background through the writing pane, exposing recognizable planet and star shapes over the editor when floating above the wallpaper. Hyprland confirmed ReasonNotes was native Wayland and floating and there was no matching opacity override in the ReasonNotes window rule. Dolphin next to the same wallpaper remained a sufficiently opaque, grey frosted window.

## Fix
Preserve the exact live palette color and blurState.active behavior, but increase native-blur surface opacity: writing pane 0.98 (2% wallpaper contrast leak), sidebar 0.97 (3%), controls 0.985, hover surface 0.99. No native-blur capability -> existing exact solid 1.0 fallback. No changes to standalone Dark or Light. Preserve top-edge reflections and native compositor blur. The main window root is still transparent when native blur is active but the full visible panels now supply readable tinted backgrounds.

## Tests and deployment contract
- Strengthen Qt QML smoke test for native-blur bounds, exact live palette colors, wallpaper-leak upper bound, opaque fallback, and Dark/Light modes.
- Repair an already-stale desktop installer regression expecting an old scalable SVG, replaced earlier by exact-sized PNGs when the user selected the YAMIS icon. This does not alter installer behavior.
- Qt/QML focused 54/54 passed; full Fedora test suite 233/233 passed.
- Next: checkpoint this tested source, install new immutable Fedora release via official installer preserving old rollback and verify user-data manifest byte-for-byte unchanged. Because an existing live ReasonNotes window may hold an unsaved editor draft, do not force-quit it. Ask user to close/reopen that window when convenient to load new styling. Confirm look over bright wallpaper in both floating and tiled modes.
