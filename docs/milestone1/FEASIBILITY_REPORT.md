# KDE Connect Feasibility Report

## Decision

Recommend **KDE Connect** for ReasonNotes Milestone 2.

The physical POCO test established dependable two-way local-network exchange
with acceptable interaction: after one-time directory setup, Linux performs one
sync invocation and Android performs one **Sync now** tap. Direct Android
targeting opened no chooser. Incoming files were found and imported from the
dedicated directory without per-file file-manager work. Exact heads and body
hashes converged in both directions.

This is foreground, user-initiated synchronization, not continuous background
sync. That limitation fits the desired simple “press Sync” workflow.

## Verified behavior

- Human-readable canonical Markdown revisions crossed in both directions.
- Linux observed KDE Connect completion and ReasonNotes durable import as
  separate events.
- Android preserved its SAF tree grant and scanned the dedicated exchange
  directory on user action/resume.
- Keep Linux and Keep Android produced deterministic multi-parent resolutions;
  Skip changed nothing.
- Both conflicting heads and their readable device-labeled previews remained
  available until the user selected a winner.
- Superseded edits and tombstones remained in immutable history after
  resolution.
- Duplicate and renamed duplicate delivery were idempotent.
- Offline requests failed visibly. Default sync did not repeatedly resend, and
  explicit retry reached an observable bounded `needs-attention` state.
- Stale resolution did not absorb a concurrent third head. Children and a
  resolution arriving before their base remained pending; the late base
  activated the correct resolution without resurrecting an ancestor.

See `PHONE_TEST_RESULTS.md` for exact IDs and hashes.

## Limitations and risks

- KDE Connect provides no application-level acknowledgement that the receiving
  ReasonNotes instance imported a revision. A transfer request, KDE Connect
  completion, and durable import are different facts. Milestone 2 should add a
  small ReasonNotes receipt envelope before showing “confirmed” delivery.
- Android incoming discovery occurs when ReasonNotes starts/resumes or the user
  taps Sync; KDE Connect does not call back into the app.
- This feasibility build uses fixed peer IDs. Dynamic peer selection is product
  work, not a Milestone 1 requirement.
- HyperOS blocked ADB installation. Manual file-manager installation worked and
  is a development-device constraint, not evidence about store/release install.
- The test-only instrumentation runner and a deliberately interrupted large
  in-flight transfer were not run. Direct physical assertions, partial-file
  quarantine, offline failure, immutable history, and restart reconciliation
  cover the safety model, but not that exact transport interruption.

## Why not the alternatives

Syncthing would add another companion application and background folder-mirror
model even though KDE Connect already meets the verified interaction and
reliability gate. A built-in LAN protocol would make ReasonNotes own discovery,
authentication, transport, retry, and platform background behavior. Neither
maintenance cost is justified by the current evidence.

Reconsider the transport only if later use shows unstable direct targeting,
unacceptable foreground interaction, or receipts cannot make delivery state
predictable.

## Milestone 2

The actionable plan is
`docs/superpowers/plans/2026-10-12-milestone2-minimal-notes-slice.md`.
It first adds durable import receipts, then builds a minimal GTK 4 Fedora shell
and native Android shell with Markdown CRUD, search, automatic saving, and the
required explicit conflict choices. Flutter remains deferred because this slice
can reuse both proven platform cores without adding a third sync implementation
or a platform-bridge layer.
