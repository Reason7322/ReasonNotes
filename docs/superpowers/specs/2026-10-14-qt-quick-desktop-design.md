# ReasonNotes Qt Quick Desktop Design

## Purpose

Task 3 delivers the first operational Fedora notes interface without changing
ReasonNotes' offline-first data model. The desktop app is a thin Qt 6 / Qt
Quick shell over `NoteService` and the existing KDE Connect synchronization
core. Immutable revision history remains the only durable note-content source.

This specification supersedes the GTK direction previously written for
Milestone 2 Task 3. It does not change the native Android direction in Task 4.

## Technology and process boundaries

- Python 3.14, PySide6 6.11.2, Qt 6.11.2, Qt Quick, QML, and Qt Quick Controls.
- No GTK and no Qt Widgets as the primary interface.
- No second note store, cloud transport, synchronization protocol replacement,
  Android migration, visible development launch, or live-note modification.
- KDE Connect remains the transport. Transfer request, durable import, and
  receipt confirmation remain separate evidence.
- Automated tests use disposable repositories and fake KDE Connect adapters.

## Window and visual structure

The default window is approximately 1200 by 800 logical pixels. A horizontal
`SplitView` contains a permanent left sidebar, initially about 240 pixels
wide, and a main panel that takes the remaining space. The divider is
draggable, the sidebar cannot be collapsed away, and its width is persisted
with Qt settings.

The interface is dark, flat, and restrained:

- sidebar: `#353535`
- editor: `#101010`
- primary text: `#F0F0F0`
- borders: `#454545`

A reusable QML theme object owns colors, spacing, typography sizes, and minimum
layout dimensions. Components consume that object instead of scattering style
literals. This is the customization seam for future typography, accent, theme,
and layout options; Task 3 does not add a settings screen.

## Sidebar

The always-visible sidebar contains, in order:

1. ReasonNotes heading.
2. New Note button.
3. Save button.
4. Sync button.
5. Search field.
6. Scrollable note list with title, preview, conflict indicator, and accurate
   outgoing delivery state.
7. Local-save and synchronization status at the bottom.

Deletion is available beside the primary actions and requires confirmation;
it creates the normal tombstone revision and explains that immutable history
remains recoverable.

The note model updates through Qt model notifications. Search uses
`NoteService.list_notes(query)`, including its Unicode-aware matching and
deleted-note exclusion.

## Editor and draft lifecycle

The main panel normally contains a plain Markdown `TextArea`. It supports
ordinary selection, navigation, undo, and redo. The first nonempty Markdown
line continues to derive the title through `NoteService`.

Draft behavior is:

- editing marks the draft dirty and restarts a 500 ms single-shot autosave;
- Ctrl+S and Save request an immediate save;
- switching notes, starting synchronization, and closing the window first
  flush the active draft;
- identical content creates no new revision;
- UI state becomes Saved only after repository persistence succeeds;
- a failed or stale-base save keeps the exact draft in the editor, reports an
  error, and blocks the switch, close, or synchronization action that required
  the flush;
- completion of background synchronization never replaces a dirty or failed
  draft silently.

Ctrl+N creates a note, Ctrl+S saves, and Ctrl+F focuses search.

## Presenter and Qt bridge

`NotePresenter` is a UI-independent application coordinator over
`NoteService`, delivery-state inspection, and an injected synchronization
runner. It owns the active note identity, draft, dirty/save state, filtered note
rows, sync status, and the currently observed conflict comparison. It never
writes note content outside `NoteService`.

A PySide6 bridge exposes presenter state as Qt properties, signals, slots, and
`QAbstractListModel` instances for QML. It refreshes models only after a
specific application event rather than polling or periodically rebuilding the
interface. Synchronization runs in a Qt thread-pool worker; result application
and model notifications return to the GUI thread.

## Selective synchronization

The Sync dialog provides:

- Sync all notes.
- Choose notes to sync.
- Searchable checkboxes, Select All, Clear Selection, and an action disabled
  when the selection is empty.
- Pending, transfer-requested, needs-attention, and confirmed states derived
  from the delivery ledger without calling a request “synchronized.”

`sync_linux` gains an ordinary optional note-ID selection parameter distinct
from `force_ids`. It always scans/imports incoming envelopes first and always
processes eligible receipts. For outgoing note revisions:

- `None` sends every ordinarily eligible revision;
- an explicit set sends only ordinarily eligible revisions whose immutable
  envelope belongs to a selected note;
- an empty set sends no note revisions;
- all eligible revisions for a selected note are included, preserving pending
  ancestry, conflict-resolution dependencies, and tombstones;
- retry bounds, confirmation state, and duplicate protection remain unchanged.

Selection controls only Fedora's outgoing note revisions. It does not control
Android's outgoing selection.

## Conflict view

An unresolved note uses a dedicated main-panel conflict view while the sidebar
stays visible. Full bodies are loaded from immutable revisions, never from the
truncated `ConflictCandidate.preview`.

Ordinary Linux-versus-Android conflicts appear side-by-side with explicit
device labels, full content, Keep Linux, Keep Android, and Skip. Deleted heads
are visibly identified. Every candidate has its own exact revision ID. If an
origin has multiple heads, the user selects a specific candidate; the
presenter never chooses arbitrarily.

Before a keep action, `NoteService.resolve` revalidates the head snapshot
captured by `NoteService.conflict`. A changed head set rejects the action and
refreshes the comparison. Skip writes nothing. The view explains that
superseded revisions remain recoverable. Ordinary editing is unavailable until
the conflict is resolved or the user selects another note.

## Status and failure behavior

Local status is one of Idle, Dirty, Saving, Saved, or Error. Sync status is
Idle, Running, Completed with evidence counts, or Error. A completed KDE
Connect request is displayed as transfer-requested unless a receipt has
actually confirmed durable application import.

Synchronization failures remain visible. Incoming imports and conflicts are
reported separately from outgoing requests and confirmations. No error path
clears the active draft or immutable history.

## Automated acceptance

Focused tests cover presenter create/load/save, coalesced autosave, failed-save
draft retention, note-switch and sync flushing, Unicode search, deletion,
selective versus all-note sync, ancestry/tombstone delivery, receipt handling
independent of selection, evidence-accurate status, full conflict content,
exact multi-head selection, Skip, and stale conflict refresh.

An offscreen Qt smoke test loads the real QML component tree against an isolated
repository. It proves import/component/property wiring only. Visual quality,
interactive behavior on a real display, and physical POCO receipt
synchronization remain separate owner acceptance gates.
