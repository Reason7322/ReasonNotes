# Android selection toolbar spacing

Original observable goal: keep ReasonNotes Android note selection/deletion fully functional while fixing the screenshot's cramped horizontal selection toolbar, without touching any notes, histories, pairing or sync behavior.

- Selection count now occupies a separate first row instead of competing with controls.
- The second row has a 48dp overflow button, which remains essential for Pin selected, Unpin selected, and Select all visible.
- Delete and Cancel are equal-weight 48dp-high buttons using the remaining row width.
- Left gaps: 16dp between overflow and Delete, 10dp between Delete and Cancel; vertical count-to-actions separation: 16dp.
- Selection, cancel, overflow, and permanent-delete confirmation wiring stays unchanged.
- Android offline testDebugUnitTest and assembleDebug passed. No note operation is performed by the layout.
- Physical device visual review after installation is necessary; protect app data and keep Android original signing identity.
