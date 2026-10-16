# Shift-click bulk permanent deletion, Fedora ReasonNotes

## Original observable user goal
The user wants to hold Left Shift and click individual note rows on the desktop to toggle a set of non-adjacent notes, then bulk delete exactly those notes. They explicitly chose toggling individual rows, NOT selecting an inclusive range. The final product must preserve the existing functional app, responsive startup, F5 receive refresh, note content/history for unaffected notes, and immediate cross-device permanent deletion when both apps are open and connected.

## Acceptance criteria and behavior
- Shift + left click toggles only the clicked note in the bulk set; clicking it again removes it. A normal click still opens a note and clears bulk selection.
- Bulk selection does not switch/open an editor, so selected note IDs and unsaved drafts are not implicitly saved or rewritten while choosing targets.
- Checkmarks, distinctive selected backgrounds, selection count and a Clear action make selected targets apparent. The Delete button shows Delete (N) and stays enabled when the selection is nonempty, even without an active editor note.
- A destructive dialog freezes exact selected IDs, displays the exact count (including 1) and unambiguous permanent-deletion warnings, and offers cancel. An ordinary no-selection Delete retains existing single-note behavior.
- Multiple notes are removed only after one confirmation via the same content-free durable purge fence used by prior single-note deletion, triggering established cross-device purge propagation. No unrelated note revisions or history are touched. No additional normal content transfer is requested.
- Selection persists across search filters but prunes notes that no longer exist when the model refreshes. Pending confirmation never adds notes if selection changes later.
- A stale/pre-purged selection fails closed without deleting another live note. Partial and interrupted deletion failures are reported, not falsely declared successful; already-purged notes remain fenced against resurrection.
- UI supports both English and Polish. No Android code update required.

## Implementation
- Qt bridge stores separate bulk-note selection independent from sync-dialog selection; exposes counts, selected IDs, and frozen pending-delete batch with a bulk/single flag.
- Sidebar note-row MouseArea inspects actual mouse modifiers, rather than relying on modifier-less AbstractButton.clicked; keyboard ItemDelegate activation remains available.
- Presenter invokes existing NoteService.delete_note / Repository.purge_note once for each frozen target and performs a single list refresh; source repository manages durable purge fences, receipts and remote propagation.
- Main dialog handles frozen count, singular/plural wording and cancel. Existing no-selection single-note Delete still works.
- No production notes, app installations, or phone state changed during development.

## Validation
- Fedora full Python/Qt suite: 214 passing (baseline 206 + 8 focused safety/UI cases), including QTest Shift-modified mouse clicks, toggling off, normal click clearing, noncontiguous selection, exact-count dialog, destructive confirmation, persistent per-note fences, preservation of unselected history, cancel, across-filter selection, frozen targets, dirty draft, stale selection failure, disk failure mid-batch, and interrupted cleanup after committed fence.
- Existing F5, startup, sync, and deletion tests still pass. Offscreen app launch validation shows the new bindings compile and have no feature-specific errors; preexisting Qt context-destruction warning may still appear for unrelated properties.
- Pending protected action: user approval to install Fedora-only release; then confirm on actual app/monitor, without performing permanent deletion of actual user notes unless explicitly authorized.
