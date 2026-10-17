# Android saved-note editor initial caret

## Original observable end goal
Opening any existing saved note on Android should immediately let the user continue typing at the end of its content, not the beginning. Retain existing desktop behavior, history, drafts, autosave, sync, permanent deletion and the normal ability to move the cursor manually while editing.

## Applied
- After loading and binding the actual saved or recovered draft body, focus the body field and set its selection to the Java EditText character length (UTF-16 offset). Applies equally to empty notes, nonempty notes, long multi-line text and Unicode.
- Request the soft keyboard once when the editor is loaded and focused, so typing continues from the end without an extra tap. The input method request does not alter note content.
- Only set the cursor during initial content binding, before TextWatcher attachment. Never change selection on later user edits, app backgrounding, saving or syncing.
- No repository, autosave, sync, deletion or preference behavior changed.

## Checks
- Offline Android testDebugUnitTest assembleDebug passed including contract checks for multiline/Unicode offset and initial binding order.
- Final acceptance: commit, verify signing identity, in-place install with byte-for-byte private file preservation, launch success, inspect the actual saved note manually on the phone to confirm cursor and keyboard. Do not open or edit private note content remotely merely to test cursor.
