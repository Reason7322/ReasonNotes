# Qt Desktop Checkpoint

Milestone 2 Task 3 provides the Fedora desktop application in
`linux-app/reasonnotes.py`. It uses Qt 6, Qt Quick Controls, QML, and PySide6;
`NoteService` and `Repository` remain the only application and persistence
layers.

## Launch contract

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
wiring, dark-theme loading, long-document scrolling, selected-sync enablement,
and background sync completion without touching live notes. It does not
establish visual quality, real-display keyboard or pointer interaction, KDE
Connect runtime behavior, or physical POCO receipt confirmation.
