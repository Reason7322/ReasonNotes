# Direct Exchange Access Design

## Intent

Make Android reliably import KDE Connect JSON v1 carriers on Reason's POCO,
where the system folder picker refuses every directory and the existing SAF
grant does not enumerate files that are visibly present and indexed.

## Selected approach

Reason approved Android's special All files access permission for this
personal, sideloaded application. ReasonNotes will use that permission only to
read the fixed `/storage/emulated/0/ReasonNotes Exchange` directory. Existing
JSON v1 validation, current-state application, acknowledgments, conflicts and
deletions remain unchanged. The existing SAF receiver remains a compatibility
fallback when the special permission is unavailable.

The Settings page will replace the unreliable folder picker with a clear
storage-access status/action. The obsolete foreground LAN deletion pairing UI
will be removed; deletion propagation remains part of ordinary JSON sync.

## Constraints

- No new transport protocol, cloud service, background daemon, or broad scan.
- Direct file access is confined in code to the one fixed exchange directory.
- No personal-note contents are inspected by tests.
- Preserve all current notes, ledgers, pending states and the approved UI.
- Build and hash a candidate, but do not install it without explicit approval
  for that exact APK.

## Alternatives considered

- Keep SAF and choose another folder: rejected because DocumentsUI refuses all
  folders and the valid existing grant does not enumerate KDE Connect files.
- Replace KDE Connect transport: rejected as unnecessary complexity because
  KDE Connect delivery and the JSON carrier are already proven correct.

