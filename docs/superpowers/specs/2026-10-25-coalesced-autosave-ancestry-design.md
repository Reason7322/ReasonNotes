# Coalesced Autosave Ancestry Repair

## Intent

ReasonNotes must transmit the latest saved state even when local autosave has
created multiple states before synchronization. A peer that still has an older
known ancestor must recognize the latest state as a descendant, while genuine
independent edits must still become an explicit conflict.

## Selected approach

While a note has a pending unsynchronized local state, each replacement
autosave will retain that pending state's original parent set instead of
extending it. A -> B -> C before synchronization therefore transmits C directly
as a child of A. An edit after a fully confirmed state continues to name that
confirmed current state as its parent. Android and Fedora will implement the
same bounded rule; parent lists do not grow with ordinary autosave count.

The alternatives considered were retaining and transmitting every intermediate
autosave, which restores unwanted history and transfer complexity, or accepting
unknown-parent states blindly, which could silently overwrite a real conflict.
Both were rejected. Transitive ancestor accumulation was also considered and
rejected because its state records would grow without a hard bound.

If two complete valid states cannot be ordered from their bounded ancestry,
the receiver will durably preserve both as an explicit conflict. It will not
return an unexplained `needs-attention` dead end. Malformed envelopes and
states blocked by a durable deletion fence continue to fail closed rather than
being presented as valid conflict choices.

## Boundaries

- Keep the existing state-v2 and acknowledgment formats.
- Do not add an immutable history store or transmit superseded autosaves.
- Preserve exact sibling-conflict behavior: two independent edits made from the
  same ancestor still have matching ancestor sets and must show both versions.
- Apply the bounded coalescing rule to local upserts and deletions on both
  platforms.
- Convert valid, otherwise-unorderable live states into explicit conflicts.
- Do not mutate the current physical-test repositories while developing.

## Verification

Regression tests must first reproduce the observed sequence: import state A,
save local B, save local C, then apply C to a peer that still has A. The test
must fail with current code and pass after the repair. Existing conflict tests
must continue to prove that independent edits from A remain conflicts. Focused
platform suites and candidate builds follow before any new installation.
