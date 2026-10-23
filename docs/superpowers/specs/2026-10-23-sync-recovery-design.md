# ReasonNotes ordinary synchronization recovery design

## Intent

Meet the product contract: Sync sends current changes, conflicts preserve both
versions for an explicit choice, and deletions converge without resurrection.
Revision history and ledgers remain internal implementation details.

## Chosen approach

Repair the existing planner rather than replacing the proven JSON v1 transport.
When a pending local revision depends on a local ancestor whose bounded retry
state is `needs-attention`, include that ancestor in the same carrier as the
pending descendant. This is a user-initiated recovery caused by a newer change,
not an automatic retry loop. Unrelated eligible notes continue in the batch.

Alternatives rejected: resetting ledger rows would destroy evidence; sending a
descendant without its ancestry could make the peer unable to reconcile it; a
new protocol or background service is unnecessary.

## Safety and behavior

- Never change or discard immutable revisions or ledger history.
- Advance attempts only after KDE Connect accepts the share request.
- Keep durable receipts as the only confirmation of peer import.
- Preserve existing explicit conflict selection and purge-fence deletion logic.
- Failed requests leave pending changes eligible for another ordinary Sync.
- No UI redesign, protocol change, migration, installation, or live sync.

## Acceptance

Synthetic tests cover exhausted-ancestor recovery, independent-note progress,
rejected transfer retryability, conflict convergence, deletion propagation and
restart persistence. Physical verification remains separate.
