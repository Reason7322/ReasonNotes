# Fedora desktop receives KDE Connect notes automatically — 2026-10-15

## User-visible failure

The first native Fedora desktop release installed successfully and launched
through Noctalia, but the user saw an empty note sidebar even after sending a
note from Android. The incoming Android ZIP was already physically present
in ~/Downloads; its 11 revisions were previously imported only into a
separate temporary acceptance repository. The installed desktop launcher's
default --received-dir was None and it never scanned incoming files at
startup, so the real desktop repository remained empty.

## Repair

- Desktop launcher defaults --received-dir to ~/Downloads, where KDE Connect
  receives the note ZIP on this Fedora/Hyprland machine.
- At normal startup, scan_inbox imports validated ReasonNotes revisions into
  the actual XDG app repository *before* loading the Qt note presenter, which
  means imported notes appear in the sidebar when the window opens.
- Normal user-initiated Sync uses the same received-dir automatically.
- Startup calls receive-only scan_inbox, never sync_linux; **it does not
  request transfers or consume retry attempts**.
- --no-auto-receive allows a safe opt-out; explicit --received-dir and
  --root overrides remain available.
- Valid receipt envelopes in global Downloads that reference an entirely
  unknown revision and have no matching delivery ledger entry are ignored.
  These receipts belong to prior disposable repositories and cannot confirm
  a real note in the new library. Receipts matching a known revision or ledger
  entry still undergo existing strict confirmation validation. Invalid
  envelopes are not silently accepted.
- The incoming archive is never extracted into Downloads, moved, deleted
  or changed. Note content is imported into canonical immutable history,
  not into an alternate synchronized note store.
- No Android changes; no background sync, polling, or automatic outgoing
  share requests are added.

## Verification

- Full Python and Qt unit tests pass including zero-argument startup,
  automatic archive import, ignoring unrelated historical receipts, and
  idempotent repeated launch with unchanged archive.
- Real data dry-run used a fresh disposable Fedora repository and **all three**
  downloaded verified Android archives, producing 33 historical revisions and
  two visible note heads: `Test Note` and `Sen`.
- Existing original Android ZIP files, disposable earlier test repositories,
  and user's installed live library were not altered by the dry-run.

Installation is versioned and leaves earlier installed releases in place.
The existing live ReasonNotes window must be closed and reopened to run the
new code. Avoid terminating a live process with unsaved edits.
