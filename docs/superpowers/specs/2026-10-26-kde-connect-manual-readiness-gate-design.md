# KDE Connect Manual Readiness Gate Design

## Intent

Prevent ReasonNotes from attempting an Android transfer while KDE Connect is
not ready. A sync request must never be presented as proof that the peer
received or imported a note.

## Chosen approach

Android uses a small foreground preflight before every user-initiated sync:

1. Verify that the KDE Connect package and launch activity are available.
2. Open KDE Connect without creating a carrier or changing synchronization
   state.
3. When the user returns to ReasonNotes, ask them to confirm that the Fedora PC
   is visible and connected in KDE Connect.
4. Only the explicit confirmation continues the original requested selection.
   Cancel leaves every pending state and acknowledgment untouched.

Fedora keeps its existing machine-readable `kdeconnect-cli` reachability check.
Android does not claim to prove Fedora-side reachability: KDE Connect exposes no
supported Android API that reports what the Fedora daemon currently recognizes.

## Status and durability

- Opening KDE Connect is not a transfer request.
- Manual readiness confirmation is not delivery or synchronization success.
- A successful share-activity launch is only a transfer request.
- The sender remains pending until the peer durably imports the state and the
  existing ReasonNotes acknowledgment path establishes confirmation.
- Failure or cancellation preserves retryable pending work.

## Approaches considered

- **Chosen: launch KDE Connect, then require manual confirmation.** It matches
  the single-user workflow and does not add another transport.
- **Rejected: assume an installed or launched KDE Connect app is reachable.**
  This caused the observed false-positive flow.
- **Rejected for this milestone: add a custom LAN readiness handshake.** It
  duplicates connectivity infrastructure and expands the protocol without a
  demonstrated need.

## Constraints

- Preserve the current JSON v1/v2 carrier formats, note selection, conflicts,
  deletion behavior, and approved interfaces.
- Do not change global KDE Connect configuration or request broad permissions.
- Do not create a carrier, remove an acknowledgment, or mutate transfer state
  until the user confirms readiness and the existing send path starts.
- Real keyboard/navigation behavior and Android lifecycle restoration must keep
  the originally requested sync selection intact.
