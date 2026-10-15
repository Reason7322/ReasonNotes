# Qt Desktop Checkpoint

Milestone 2 Task 3 provides the Fedora desktop application in
`linux-app/reasonnotes.py`. It uses Qt 6, Qt Quick Controls, QML, and PySide6;
`NoteService` and `Repository` remain the only application and persistence
layers.

## Approved desktop visual direction

The desktop shell follows **Concept C — Minimal Writing**. Its permanent,
resizable sidebar defaults to 300 logical pixels and uses a restrained action
hierarchy: product heading, gold New Note action, Save, Sync, Delete, search,
notes, and local/phone status. Note rows use a narrow gold selection marker,
single-line previews, and explicit conflict and delivery status without card
borders around every row.

The editor uses a centered 920-pixel maximum reading column rather than an
edge-to-edge text field. It includes a derived presentation heading, a compact
local-save/phone-delivery bar, an 18-pixel plain-Markdown source editor, and a
functional empty state. The heading is derived from the first nonempty
Markdown line; it is not a second persisted title field. The editor narrows
responsively at smaller window sizes and retains normal scrolling.

## Editable titles and title-only navigation

The heading above the editor is a directly editable title field. It and the
body editor are two views of **one** persisted Markdown document. A title
edit serializes the first nonempty line as a level-one heading; the body
remains unchanged. The body editor excludes the heading and the one optional
blank separator following it. Older notes with a plain-text first line are
displayed without rewriting the source until a deliberate edit occurs.

New notes focus the title field. Body text entered before naming a new note
uses an explicit `# Untitled` heading so body content cannot turn into the
title unexpectedly. Both fields retain the same autosave, immutable-history,
race protection, and conflict restrictions.

Opening a different note automatically scrolls its Markdown body to the last
visible entry when content exceeds the available editor height. Short notes
remain at the top. The one-shot position change waits for Qt text layout and
does not repeat when the user scrolls, edits, or saves the same note. The
title and status bars stay fixed while the body scrolls. An offscreen real-QML
regression covers long→short→long switching, save, and preserving manual scroll.

The permanent sidebar displays **titles only** with a conflict indicator;
content previews and per-note delivery labels are not shown in its note
rows. Detailed delivery information remains in the existing app status and
sync controls.

## Consistent action icons

Save, Sync, and Delete use official Lucide SVG artwork loaded as 24-pixel
Qt Quick Images, rasterized at the display's physical pixel density. Their
fixed-size slots align all three labels; normal, hover, and disabled states
retain the Concept C palette. Original SVGs and upstream license are in
`linux-app/qml/icons/lucide/` alongside theme-colored display variants.
The New Note plus sign is unchanged. Button actions and synchronization
behavior remain unchanged. LiquidGlass theme switching is deferred.

The theme is centralized in `linux-app/qml/Theme.qml`. The initial palette is:

- main `#15171C`, sidebar `#1D2026`, and secondary surfaces `#262931`;
- primary text `#ECEEF3`, secondary text `#A2A7B2`, borders `#353941`;
- accent `#DCAF48` and selected note `#302C26`.

Sync and conflict views share the same surfaces, type scale, spacing, button
hierarchy, and evidence language. Their existing selection, exact-revision,
stale-choice, and immutable-history behavior is unchanged. Rich Markdown
rendering is intentionally not part of this checkpoint; editing remains
plain source so no independent document model can diverge from saved content.

## Historical developer launch contract (superseded)

The original developer-only invocation described below is retained as
historical context. The later Fedora-native desktop checkpoint installs a
normal application menu entry and supports zero-argument startup with an
isolated XDG note repository; see `FEDORA_NATIVE_DESKTOP.md`.

## Launch contract — original prototype

The launcher deliberately requires explicit repository and transport paths:

```bash
PYTHONPATH=src python3 linux-app/reasonnotes.py \
  --root /path/to/reasonnotes-repository \
  --inbox /path/to/kde-connect-inbox \
  --device-id 32-character-kde-connect-device-id
```

`--target-device` defaults to `android`. The application never infers or
creates a live-note location outside the supplied repository root.

## User-visible evidence states

- `Pending` means a local revision is eligible for an outgoing request.
- `Transfer requested` means KDE Connect accepted a transfer request; it does
  not mean ReasonNotes imported the revision on the peer.
- `Confirmed` means a valid application receipt was durably imported.
- Sync completion separately reports imports, transfer requests,
  confirmations, and receipt requests.

Selected-note synchronization filters only ordinary Fedora outgoing note
revisions. It still scans incoming files and processes required receipts.
Explicit forced retries retain their existing independent semantics.

## Verification boundary

The automated QML smoke test loads the real component tree with an offscreen
platform and disposable repository. It verifies imports, property/model
wiring, the Concept C palette and control hierarchy, default and responsive
geometry, empty-state behavior, long-document scrolling, secondary-view
styling, selected-sync enablement, and background sync completion without
touching live notes. `linux-app/render_concept_c.py` renders deterministic
screenshots from disposable sample notes, and was exercised at 2048×1152 and
900×600 logical pixels. Offscreen images establish layout and clipping
evidence, not real Wayland rendering, keyboard/pointer interaction, KDE
Connect runtime behavior, or physical POCO receipt confirmation.
