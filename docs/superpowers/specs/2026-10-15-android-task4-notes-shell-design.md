# ReasonNotes Android Task 4 Design

Date: 2026-10-15  
Branch: `forge/android-notes-shell`  
Base: `3af3683851b0c876ee9bb693fc38a29198997f1c`

## Purpose

Task 4 turns the existing native Android synchronization probe into a usable
offline-first notes application while retaining its proven immutable history,
SAF exchange directory, KDE Connect sender, receipts, and delivery ledger.
The result must implement the approved card-based mobile experience without
changing the synchronization protocol or creating another durable note store.

Task 4 ends with a tested debug APK and a pushed Forge checkpoint. Physical
installation and two-device acceptance remain Task 5 and are not authorized
by this specification. The separate White, Dark, and LiquidGlass milestone
starts only after Task 4 is committed, pushed, and remotely verified.

## Existing-package preservation

The project keeps the existing Android application ID
`org.reasonnotes.probe`. Therefore a future APK installation would target the
same installed package rather than create a second application. An in-place
upgrade normally preserves internal files and preferences only when Android
accepts the APK as signed by the same certificate. A mismatched signature
would block the upgrade; uninstalling to bypass that error would erase the
existing application data and is prohibited.

Task 4 source preserves the existing data locations and keys:

- immutable repository root: `files/probe-repository`;
- delivery state: `files/delivery-state`;
- exchange preference file: `reasonnotes-exchange`;
- persisted SAF key: `tree-uri`;
- receipt state beneath the repository root;
- current verified Linux KDE Connect device ID as the initial configured
  value unless the user deliberately edits it.

Before any future physical installation, Task 5 must perform a read-only
preflight that records the installed package/version/signing identity and the
candidate APK signing identity. It must also inventory the repository and
preference state that the existing app can expose. If signature compatibility
is not established, installation must stop. The fallback is a separately
planned export/import or alternate-package migration that copies immutable
history, delivery state, receipt state, device configuration, and the SAF URI
grant where Android permits it. Task 4 does not install, uninstall, clear,
backup, or migrate the live package.

## Application architecture

### Repository remains authoritative

`ProbeRepository` remains the only durable note-history authority. Every
successful create, edit, delete, or conflict choice produces an immutable
`RevisionEnvelope`. Generated current files remain rebuildable views.

The repository gains narrowly scoped application operations needed by the
service:

- enumerate complete revision history and note IDs;
- create a revision against an explicitly observed single head;
- persist a conflict-resolution revision whose parents are the exact observed
  heads;
- reject writes when the current heads differ from the caller's base;
- expose full current heads and ancestry for synchronization planning.

No operation selects a winner by timestamp. Empty or incomplete ancestry is
never promoted to current content.

### Markdown title/body compatibility

A Java `NoteDocument` component mirrors
`src/reasonnotes_app/note_document.py`:

- the first nonempty Markdown line is the logical title;
- title and body are independently editable in the UI;
- a deliberate title edit replaces or creates a Markdown heading;
- a deliberate body edit preserves the current title line and surrounding
  legacy layout where possible;
- opening, listing, searching, or closing an unchanged legacy note does not
  rewrite it or create a revision;
- there is no separate synchronized title field.

### NoteService

The native `NoteService` presents application behavior over
`ProbeRepository`:

- create, open, edit, save, and tombstone-delete notes;
- derive title, preview, modified time, conflict state, and delivery state;
- perform Unicode normalization plus locale-independent case folding for
  search, including Polish text;
- exclude deleted notes from ordinary lists while retaining tombstones for
  synchronization;
- avoid revisions when serialized Markdown is unchanged;
- inspect full conflict heads and resolve only an exact current revision;
- reject stale edits and stale conflict choices;
- reconstruct summaries and editable state from immutable history after
  restart.

The service does not know about Android widgets or activities.

### Draft recovery

`DraftStore` is device-local recovery state, not synchronized note history.
Each draft records the note ID, title text, body text, and immutable base
revision ID. Editor changes update this recovery snapshot before the debounced
history save is considered complete.

On successful repository save, the matching recovery entry is removed. After
restart, a draft is restored only when its base still matches the current
single head. If a remote import changed the base or introduced a conflict, the
draft remains recoverable and the application refuses to overwrite the new
history. Save failures keep the exact draft and show an error state.

Creating a note writes its initial immutable revision before opening the
editor, so no unbound draft can be silently discarded.

### Local pin state

`PinStore` persists a set of note UUIDs in Android-local preferences. Pinning:

- never edits Markdown;
- never produces a revision;
- never changes receipts or the delivery ledger;
- survives process restart;
- removes stale/deleted IDs from visible sections without destroying their
  recorded preference unnecessarily.

Cross-device pin synchronization is deferred. The future setting “Sync pinned
status across devices” is not displayed as operational in Task 4.

### Configuration

`AppPreferences` owns Android-local settings that are not note history:

- KDE Connect target device ID, initialized to the already verified Linux
  target;
- note ordering preference where implemented;
- local UI preferences;
- Task 4-compatible future theme key, without exposing nonfunctional theme
  choices before the theme milestone.

The existing `ExchangeDirectory` continues to own the persisted SAF URI and
permission. Task 4 never clears or silently replaces it.

## Native Android interface

The implementation uses Java 17, standard Android views, and the existing
AndroidX dependencies. It does not introduce Compose, Flutter, a web view, a
rich-text editor, or a second navigation framework. UI metrics, colors,
typography, shapes, and status colors are centralized so the later theme
milestone can replace tokens without restructuring screens. Task 4 colors are
temporary and are not recorded as visually approved.

### Home

`MainActivity` becomes the notes home while retaining the verified Android 16
system-bar/inset handling.

The vertical hierarchy is:

1. temporary ReasonNotes brand and functional Settings gear;
2. full-width New Note action;
3. Pinned heading and two-column card grid when pinned notes exist;
4. Others heading and two-column card grid;
5. bottom-anchored search field above navigation/IME insets.

Cards show the title, a short single-line-normalized preview, trustworthy
modified time where available, and relevant conflict/delivery indicators.
Cards use restrained rounded surfaces rather than separate floating windows.
Long press opens only operational Pin/Unpin and Delete actions. Delete requires
confirmation and creates a tombstone revision.

The grid adapts card width to the available content width while remaining two
columns on supported phone layouts. Search filters both sections without
changing pin state.

### Editor

`EditorActivity` provides:

- back navigation with a safe save attempt;
- a separately editable title control;
- a plain Markdown body editor;
- 500 ms debounced autosave plus save on pause/back when safe;
- local-save and delivery status that never equates request with confirmation;
- an overflow menu containing only implemented secondary actions, including
  Delete and conflict access when applicable.

The editor never silently replaces active text after an incoming scan. If the
base becomes stale, it preserves the draft, enters an error/conflict state,
and requires explicit user action.

### Settings

`SettingsActivity` uses grouped rounded sections with real actions only:

- **Sync:** current target device ID, exchange-directory chooser/status, Sync
  all, and Choose notes;
- **Behavior:** only preferences implemented during Task 4, such as a real
  ordering choice;
- **About:** application version, storage/synchronization explanation, and
  evidence-state wording.

Appearance, language, advanced privacy, and shared-pin synchronization are
omitted until their behavior exists.

### Synchronization selection

The mobile Sync UI offers:

- Sync all eligible notes;
- Choose notes through an independent searchable catalogue with checkboxes,
  Select All, Clear Selection, and an action disabled when none are selected.

Selection is expressed as ordinary selected note IDs at the synchronization
planner boundary. It is not passed through `forceIds`.

Every sync operation:

1. safely flushes the active draft or stops with the draft intact;
2. scans and imports incoming revisions and receipts;
3. reconciles confirmations;
4. plans ordinary outgoing revisions for all notes or selected note IDs;
5. includes required unconfirmed ancestry and pending tombstones for selected
   notes;
6. sends required receipts regardless of outgoing note selection;
7. retains retry bounds and duplicate protection;
8. records transfer requested only when KDE Connect accepts the request.

Requested, KDE Connect completion, durable import, and receipt-confirmed remain
separate states. Android selection controls only Android outgoing revisions.

### Conflict view

`ConflictActivity` displays all current complete heads in a vertically
scrolling layout. Each candidate includes:

- Linux or Android origin label;
- exact revision identifier;
- full immutable content or explicit deletion state;
- a Keep action only for Linux/Android candidates.

Multiple candidates from one origin remain distinct and require exact revision
selection. Skip writes nothing. Before Keep, the service compares the current
head set with the inspected snapshot. A changed set rejects the stale action
and refreshes the view. Successful resolution creates one child of every
observed head and preserves all superseded revisions.

## Lifecycle and error behavior

- Potentially blocking scan, file, and sender work runs away from the UI
  thread and reports completion back to the active screen.
- Activity recreation restores note ID, search query, selection, and draft
  presentation from local state without duplicating revisions.
- Insets account for status bars, navigation bars, display cutouts, and the
  on-screen keyboard. The bottom search and editor caret remain reachable.
- File and SAF failures produce visible errors and keep drafts/selections.
- Missing exchange configuration disables transfer actions with a useful path
  to Settings; it does not clear repository or ledger state.
- An unavailable KDE Connect target reports a requested-route failure and does
  not advance delivery state.

## Verification strategy

All automated tests use temporary repositories, fake contexts/adapters where
needed, and no live device data.

### Checkpoint A — core application

Focused tests cover:

- exact Markdown CRUD and unchanged-save behavior;
- legacy title/body compatibility without read-time rewrites;
- Unicode and Polish case-insensitive search;
- autosave success, failure preservation, restart recovery, and stale-base
  rejection after remote imports;
- local pin persistence without revisions;
- deleted-note exclusion;
- full conflict content, deletion candidates, Skip, both origin choices,
  multiple same-origin heads, and stale-head rejection;
- home/editor/settings/conflict hierarchy and window-inset contracts where
  the current local JVM test setup can verify them.

Checkpoint A is committed on `forge/android-notes-shell` before synchronization
UI expansion.

### Checkpoint B — synchronization integration

Focused tests cover:

- selected versus all-note outgoing planning;
- independent sync catalogue search and selection;
- required ancestry and tombstone delivery;
- unconditional incoming scans and receipt processing;
- ordinary retry bounds and duplicate protection;
- evidence-accurate pending/requested/confirmed status;
- draft-flush failure blocking synchronization.

Final Task 4 verification runs the complete Android unit suite and
`assembleDebug`. The report records exact test counts, APK path, SHA-256,
source commit, and clean/dirty worktree state. Build and JVM results are not
reported as POCO runtime or visual acceptance.

## Checkpoint and scope boundary

After both Android checkpoints pass:

1. update the Milestone 2 plan, `docs/milestone2/`, and `PROJECT_STATUS.md`;
2. commit Task 4 implementation and evidence;
3. push `forge/android-notes-shell` to `handoff`;
4. verify the full remote SHA with `git ls-remote`;
5. stop Task 4 without installing the APK or beginning physical acceptance.

Only after that remote checkpoint exists may the three-theme milestone begin
from the Task 4 SHA on a separate Forge-owned branch. Theme screenshots and
automated checks are supporting evidence only. Final appearance requires
explicit review by the project owner and ChatGPT and is never inferred from
mechanical validation.
