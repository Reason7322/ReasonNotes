# Android Settings / Sync UX redesign — 2026-10-16

## Original user-visible end goal

The Android ReasonNotes Settings screen must stop feeling like a developer prototype: the oversized rounded-square Back control should match the new editor’s clean navigation icon, and sync choices should look intentional, aligned, visually balanced, and understandable without exposing low-level identifiers on the primary Settings screen. Preserve the operational KDE Connect exchange directory selector, paired device ID configuration, sync-all and choose-notes actions, notes sort order, immutable history / conflict / delivery invariants, and previous fast launch, editor, and partial bulk deletion fixes. Actual phone UX acceptance requires reviewing the installed screen, not just JVM test success.

## New Settings screen

- Editor-matching 24dp vector back-arrow in a borderless 48dp tappable region, consistent top padding and a restrained 'Settings' heading.
- Four distinct groups: Sync, Connection, Notes, About. The Sync group has two compact, two-line navigation rows ('Sync all notes' and 'Choose notes') with icons and chevrons, replacing the stacked oversized gold buttons. These preserve their original `SyncActivity` modes exactly.
- Connection settings now have clear separate rows for 'Exchange folder' (with accurate configured/unconfigured status) and 'Computer' (configured target). The long KDE Connect device ID and its Save/Cancel controls are hidden until the user taps Computer. Editing still calls the same `AppPreferences.setTargetDeviceId` validation and persistence. The folder chooser still uses `ACTION_OPEN_DOCUMENT_TREE` and its persisted SAF grant.
- A single 'Sort notes' row shows the current value and opens an explicit choice dialog. The preference uses the existing `AppPreferences.setNoteOrder` implementation, not a new code path.
- About is concise and human-readable; routine low-level sync diagnostics are not shown on the Settings screen.
- Visible row accessibility descriptions are refreshed after saved changes; the connection editor retains its unsaved input across Activity recreation.

## Sync screen alignment

- SyncActivity navigation uses the same icon-based back control as the editor, aligned title, clearer hint text and smaller human-readable action labels.
- Choose-notes mode keeps its search, select-all, clear, and selected-only outgoing behavior; rows sit together in a subdued card. Sync-all mode retains the one explicit Start sync action.
- Preparation of repository, sync coordinator and catalogue moved from the Android UI thread to the existing background worker. Buttons stay disabled until prepared; loading and error states are explicit.
- A sync-in-flight guard disables repeat sync requests. UI selections cannot re-enable the transfer action mid-operation. Errors are displayed without leaving controls permanently disabled.
- Receipt confirmation is still separate from requesting a KDE Connect transfer. All integrity, validation, conflict, sync-history and note-deletion pathways remain intact.

## Validation / remaining work

- Fully offline Android Gradle `testDebugUnitTest assembleDebug`: **126/126 passed**, debug APK built.
- Fedora Python/Qt suite: **194/194 passed**.
- Current phone installation was not modified by this development workflow.
- Remaining acceptance: obtain user's consent for a fresh verified private-data backup + in-place ADB installation of signed APK. On the POCO, visually inspect the new Settings/Sync layouts, test Back, Sync all (without confusing transfer request for confirmation), choose notes, Select all / Clear, exchange folder status, edit device ID (save and cancel), note sorting and keyboard resizing, and verify no crash or private-data mutation during installation. Keep automatic sync disabled and do not fire real transfer as part of a UI-only check unless user separately approves that action.
