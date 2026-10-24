# ReasonNotes simplified synchronization design

Date: 2026-10-24
Status: proposed for owner approval; no product implementation yet

## Intent

ReasonNotes must do four things well: save and edit notes on Fedora and
Android, send changes when Sync is pressed, show both complete versions of a
concurrent edit and let the owner choose, and permanently propagate deletion.
The current immutable-history, ancestry, receipt-ledger, generated-view, and
historical-carrier machinery is larger and slower than this product needs.

The selected direction is to simplify the existing implementation in place.
The physically proven KDE Connect transport and JSON carrier remain. Storage
and synchronization state become current-state based.

## Product behavior

- Each device stores one current record for each live note.
- Editing saves locally without waiting for the peer.
- Sync sends the latest pending state for each changed note. Repeated local
  edits coalesce into one pending change.
- A peer acknowledgment clears that pending change. Transfer request and peer
  acknowledgment remain distinct so the UI never reports false success.
- If both devices independently edit the same live note, both complete
  versions are shown and the owner chooses one. The chosen version becomes the
  current state and is sent normally.
- Deletion always wins over an edit. It immediately erases local note content
  and recoverable local history, then propagates to the peer. There is no
  edit-versus-delete conflict screen.
- A content-free deletion fence remains so an old carrier cannot resurrect a
  deleted note. It contains only note identity and deletion/version metadata.
- Status is limited to: synced, waiting for peer, transfer failed, or conflict.
  Failures identify the affected note where a title still exists.

## Minimal data model

### Current note

- stable note UUID
- title and body
- current change ID (content hash over the canonical record)
- parent change ID, or empty for a new note
- modification timestamp for display only, never conflict arbitration
- device ID that authored the current change

Only the current record is needed for normal display and launch. A temporary
second candidate is stored only while an edit/edit conflict is unresolved.

### Pending change

At most one pending outgoing entry exists per note. It references the current
change ID and has only current-operation state: pending, transfer requested, or
acknowledged. A newer local edit replaces the older unsent entry. Failed
requests remain retryable and do not block other notes.

### Deletion fence

The fence stores no title, body, preview, or recoverable revision. It prevents
old files from recreating the note and remains until a future explicit product
decision provides a provably safe garbage-collection rule.

### Carrier and acknowledgment

Keep the existing JSON v1 bundle/container and KDE Connect share path, but its
items carry current note changes, deletion fences, and acknowledgments. An
acknowledgment names the exact accepted change ID. Historical receipt queues
and ancestry bundles are not consulted by the normal v2 path.

## Receive rules

For a live incoming note:

1. If its change ID is already current, treat it as a duplicate and acknowledge.
2. If its parent is the local current change ID, apply and acknowledge it.
3. If local and incoming changes have the same parent, store both complete
   candidates and show an edit/edit conflict.
4. Otherwise report that this note needs attention; never overwrite content.

For an incoming deletion, permanently erase the live note and any conflict
candidates, write the content-free fence, remove obsolete pending note content,
and acknowledge the deletion. A fenced note rejects every older live carrier.

Conflict resolution creates one new current record whose parent metadata names
the two conflicting change IDs for convergence only. After both peers accept
the resolution, the losing candidates are permanently erased.

## Launch and performance

Launch reads only the current-note directory, conflict records, fences, and the
small pending queue. It never scans immutable revision history or the shared KDE
Connect directory before showing notes. Incoming exchange scanning remains a
separate operation and uses the existing processed-file index.

With seven live notes, launch should parse approximately seven current records,
not hundreds of historical revisions. Note-card rendering remains unchanged.

## Migration

Migration is local, one-time, restartable, and independently implemented on
Android and Fedora:

1. Read the old repository without modifying it.
2. Derive every current live note and any currently unresolved edit/edit
   conflict.
3. Write the v2 store into a sibling staging directory using durable atomic
   writes.
4. Reopen and validate the staged store against the derived live-note set.
5. Atomically select v2 as active.
6. Permanently remove old active history only after successful validation.

If derivation finds incomplete or ambiguous history that is not an ordinary
two-version conflict, migration stops and identifies the affected note instead
of guessing. Existing protected recovery snapshots are not modified by this
milestone; changing archival backups requires a separate explicit decision.

Old delivery ledgers and receipts are not migrated into normal v2 status. Only
the latest unacknowledged current state for each live note, plus deletion
fences, enters the new pending queue. This prevents historical failures from
blocking current synchronization.

## Compatibility and rollout

Both applications must understand v2 before physical testing. During the
controlled transition they may continue to read v1 carriers, converting a
valid incoming v1 head into a v2 current change. They emit v2 only after both
candidates are prepared. No production desktop replacement, Android install,
data migration, or physical sync occurs without a separate deployment gate.

The approved Fedora Qt Widgets/LiquidGlass interface and Android screen layout
remain unchanged except for simpler status wording backed by v2 state.

## Crash and data safety

- Current records, pending state, fences, and conflict candidates use
  write-fsync-atomic-rename persistence.
- A local save is successful after the current record and pending pointer are
  durable; the peer is not involved.
- Import acknowledgment is generated only after the imported state is durable.
- Migration never deletes the old active store before the staged v2 store has
  been reopened and validated.
- No timestamp-based winner selection and no silent overwrite are allowed.

## Approaches considered

1. **Simplify in place (selected):** retain proven transport and UI, replace
   repository/sync state. Lowest physical-integration and migration risk.
2. Replace synchronization entirely: conceptually clean but discards proven
   KDE Connect and receipt behavior and creates unnecessary deployment risk.
3. Add caches and pruning to the existing engine: smallest code delta but keeps
   the ancestry and historical-status complexity causing the current failures.

## Verification and acceptance

Synthetic isolated tests must prove:

- fast launch reads current records rather than history;
- create/edit round trips in both directions;
- repeated edits coalesce to the latest pending state;
- independent edit/edit conflict shows both complete versions and converges
  after selection;
- deletion permanently erases content, propagates, and cannot be resurrected by
  an old carrier;
- an unreachable peer leaves retryable pending work without blocking other
  notes;
- restart preserves live notes, conflicts, fences, and pending changes;
- migration preserves all current live notes and stops safely on ambiguity.

Physical acceptance remains separate: one disposable create/edit round trip,
one edit/edit conflict, one deletion in each direction, restart persistence,
durable acknowledgments, and measured Android launch/list responsiveness.

## Non-goals

- Browsable version history or deleted-note recovery.
- Cloud sync, accounts, background services, or a new transport.
- Multi-user collaboration semantics.
- UI redesign, desktop installation, Android installation, or production-data
  migration during implementation.
