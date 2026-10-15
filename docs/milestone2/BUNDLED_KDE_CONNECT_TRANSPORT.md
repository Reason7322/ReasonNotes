# Single-carrier KDE Connect transport repair — 2026-10-15

## Physical evidence and scope

The owner authorized **investigation and isolated repair**, not installation.
The currently installed Android APK is the peer-ledger repair candidate from
`sol/android-peer-ledger-merge`, SHA-256
`a1daeb9c54f45d9a2d9b4d6dc2c38bdd57af728e8f4ba0ab51c94fe7aa2075b9`.

The Android UI previously requested transfer of **21 queued receipts**.
Read-only inspection showed all 21 receipt-delivery ledger rows moved to
`transfer-requested` (one attempt each), but Fedora Downloads contained just
**one** newly received `.receipt.md` file at approximately 15:28.
The earlier 11-revision share also produced only three observed received files.
This establishes incomplete **observed** bulk delivery, not the precise cause.

KDE Connect Android 1.36.0 on the user's POCO supports
`ACTION_SEND_MULTIPLE` and parses `EXTRA_STREAM` lists; reading its
upstream `ShareActivity.kt`, `SharePlugin.kt`, `CompositeUploadFileJob.kt`,
`IntentHelper.kt` and `FilesHelper.kt` shows background work and no
synchronous per-file completion result returned to the invoking ReasonNotes
Activity. Thus merely splitting an Android share intent into more activity
launches would **not** prove completion and could complicate permission scope.

## Isolated repair

Source worktree `/home/reason/Temporary/ReasonNotes-BundleTransport`,
branch `sol/bundled-kde-transfer`, based on full verified prior checkpoint
`8966a5a302db3aad423d7aba3bdda52fcd46f017`.

1. The Android `SyncActivity.requestTransfer()` packages multiple planned
   outgoing revision/receipt files into **one content-addressed ZIP** (via
   `TransferBundle.java`) and launches a **single** KDE Connect
   `ACTION_SEND` intent using `application/zip`. A single planned file
   remains a normal `.md` share. There are never multiple share activities.
2. ZIP members are canonical `<revision-id>.md` or
   `<receipt-id>.receipt.md`. Each ID remains unchanged. Sources are checked
   before packaging and never moved, rewritten or deleted.
3. One provider grant covers only the `probe-repository/outgoing/` staged
   transfer file. ZIP names embed SHA-256 of archive bytes. Archives are staged
   using a temporary file and atomic rename before issuing any share request.
4. The Fedora `--received-dir` scanner recognizes only tightly named
   `reasonnotes-bundle-<64-lowercase-hex>.zip` archives and KDE Connect
   ` (1).zip` collisions; arbitrary unrelated ZIP downloads are ignored.
5. Fedora validates archive SHA-256, bounded archive/member sizes, member count
   (2 to 256), compression, canonical member paths, distinct member names and
   **each** canonical immutable revision or receipt envelope/identity before
   importing any member. It parses in memory; no arbitrary ZIP extraction or
   downloaded-file modification is involved.
6. Import/receipt validation remains per member using the existing repository
   and receipt-store logic. Requested transfer is still **not** confirmation;
   no receipt ID, revision ancestry or retry counter is fabricated.

Failure to create the archive, a missing source, an oversized batch, a bad
bundle hash, a malformed member, or a corrupt ZIP causes a reported error rather
than a request recorded as delivered. Android's package limits include 256
members, 16 MiB each and 60 MiB total uncompressed, leaving space beneath
Fedora's 64 MiB maximum carrier size. Large batches are not silently split or
partially sent. Generated ZIP files currently remain in the application's
private outgoing folder; automated retention/cleanup is deferred until its
safety can be evaluated.

## Tests and independent cross-runtime observation

- Linux Python unit suite: **162 tests**, all passed, including new bundle
  validation, ZIP-slip rejection, bad archive hash, duplicate IDs, collision
  names, idempotent rescan, preservation of unrelated downloads and a simulated
  **21-receipt** acknowledgment batch.
- Android Java unit suite: **109 tests**, all passed, including sorted
  deterministic ZIP output, source preservation, bad/missing source rejection,
  256-member guard and a **21-receipt** packaging case.
- Built **actual compiled Android Java** `TransferBundle.create` output from
  two new synthetic but canonical revisions. Fedora's actual Python CLI
  `reasonnotes_sync scan --received-dir ...` accepted the exact resulting
  ZIP, durably imported both revisions, queued two receipts, and classified the
  same revisions as duplicates on rescan. Archive SHA-256:
  `46a4c59b8b13d0c3df536a6cb3bcdc94506b647bb587453c847d034e05b6355a`.
  This involved only isolated test files and no transfer to a real device.
- Full Android Gradle `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL.
  The replacement APK uses the same debug-signing certificate as the currently
  installed app and the package is still `org.reasonnotes.probe`.
- Candidate artifact (not installed):
  `/home/reason/Temporary/ReasonNotes-BundleTransport-artifacts/ReasonNotes-Android-BundledTransfer-43550430.apk`
- Candidate SHA-256:
  `4355043025899f33172657eafc9e86086809a8dddce8a261ed8b779417a07ca8`,
  size **2,348,328 bytes**.
- Signer certificate SHA-256:
  `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147`.

## Deployment boundary and acceptance

**No Android APK was installed, no KDE Connect transfer was requested, and no
phone data or original Downloads file was modified in this task.**

The user's 21 Android receipts already have **one recorded request attempt**
from the prior physical test. An owner-approved future physical test should
perform a *single* selected-note sync against the new Android APK, observe
exactly one `reasonnotes-bundle-*.zip` received by Fedora, then scan that file
using the new Python receiver and a **disposable Fedora repository whose
outgoing ledger contains the intended test revision**. Check the exact receipt
`e061527e2431656c0d73ecbf9d0b6854126a29f0e7ffbc208ea593fa04b0567f`
and revision `d650007f6f8527b9fcd97e0eced22714411a0c9cd914cb463c3d1efa6db95b89`.

Do not press **Sync all** or repeatedly retry the pending receipt batch.
Physical two-way convergence is **unverified** until this controlled acceptance
passes. No UI themes, note migrations, unapproved cleanup, or unrelated Android
changes are part of this repair.
