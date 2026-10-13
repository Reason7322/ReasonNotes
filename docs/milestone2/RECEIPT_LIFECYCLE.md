# Durable Import Receipt Lifecycle

Milestone 2 Task 1 adds one small receipt for each durably imported revision.
Receipts are delivery evidence, not authentication or note history. They never
delete or replace immutable revisions.

## Canonical envelope

Receipts are UTF-8, LF-terminated, human-readable Markdown files named
`<receipt-id>.receipt.md`:

```text
---
reasonnotes-receipt-format: 1
receipt-id: <lowercase SHA-256>
note-id: <canonical UUID>
revision-id: <lowercase SHA-256>
acknowledging-device: <device ID>
destination-device: <device ID>
imported-utc: <UTC YYYY-MM-DDTHH:MM:SSZ>
---
```

There is no body. `receipt-id` is SHA-256 over the canonical metadata from
`reasonnotes-receipt-format` through `imported-utc`, omitting only the
`receipt-id` line and both delimiter lines. Device IDs use the existing
ReasonNotes delivery-ledger syntax.

The one-revision shape deliberately includes both note and revision identity.
It avoids an additional mapping format and lets the original sender compare the
receipt against its immutable history before confirmation.

## Lifecycle

1. The sender retains the immutable revision and records `pending`, then
   `transfer-requested` only after KDE Connect accepts the outgoing request.
2. The receiver validates the complete revision and durably writes immutable
   history before recording `reasonnotes-imported`.
3. After that durable import, the receiver atomically writes the receipt to its
   receipt outbox. Re-scanning a duplicate revision repairs a receipt missing
   because of a crash, but does not create a second receipt.
4. Receipt delivery has its own `pending`, `transfer-requested`, and
   `needs-attention` states with the same three-request bound. A receipt request
   is never treated as confirmation of itself.
5. The original sender parses the receipt, verifies its hash and canonical
   form, checks `destination-device` against the local device, checks
   `acknowledging-device` against the peer, loads the named revision from
   immutable history, verifies its note UUID, and requires a delivery entry
   with at least one prior request to that peer.
6. The sender atomically stores the validated receipt in its receipt inbox.
   Only then does it atomically change that revision's delivery state to
   `confirmed` and record a distinct `reasonnotes-confirmed` event.

Transfer request, KDE Connect completion, durable import, and confirmed receipt
therefore remain four separate observations.

## Failure and recovery

- Corrupt, truncated, noncanonical, wrong-device, unknown-revision,
  mismatched-note, and never-requested receipts do not alter delivery state.
- A duplicate receipt is idempotent. The stored bytes and confirmed entry remain
  unchanged; no receipt is generated in response.
- A crash after revision history but before receipt creation is repaired when
  the receiver scans the still-present revision again.
- A crash after incoming receipt storage but before delivery-ledger update is
  repaired by reconciling the durable receipt inbox at startup/scan.
- If either peer is offline, receipt files and bounded outbox state remain local
  until an explicit later sync or retry.
- Default sync requests each pending receipt once. It does not resend a
  transfer-requested receipt. Explicit retry is bounded at three attempts;
  targeted force retry remains observable.
- Receipt state is generated transport metadata. Immutable revision history
  remains the recoverable source of note content regardless of receipt state.
