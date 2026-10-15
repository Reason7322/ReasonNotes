# KDE Connect incoming-file triage (2026-10-15)

## Scope and evidence

This investigation was initiated after the owner opened the Android Task 4 application and pressed **Sync all notes**, which reported **11 outgoing revisions requested, 0 receipts sent, 0 imports and 0 confirmations**. That display proves only that Android launched a KDE Connect sharing request: the Android sender does **not** receive per-file completion receipts from the share activity.

Read-only observation on Fedora found **three** files received at approximately 12:58 local time under `/home/reason/Downloads`:

- `01f23a52be42405e6ebc6f72dc0bd7235f5369c8cab651253ceccd19ff732b95.md`
- `08f1f6625ab48149a8bdbdadf8bc5834e9d9531ecba8e12508d82ff92a2d09d5.md`
- `1792061895446` (no extension; valid-looking `reasonnotes-format: 1` envelope)

They belong to a single Android-origin note `a95b4fe5-08b2-41d3-9b9e-2807692917a5`. The latter transfer contains the root revision `3274dc239f786adf5f57e2564d508b3da3adb8cf7ab48972f15bbd7d435f195f`, while the other two are child revisions referencing absent ancestors. No other new Android share files were visible in Downloads during the read-only inventory.

The previously launched Fedora disposable notes application was configured with `--inbox /home/reason/Temporary/ReasonNotes-VisualAcceptance/2026-10-14/inbox`, a separate **empty** directory. It was not running when inspected. The Linux scanner also previously excluded all filenames without `.md` irrespective of their contents.

## Changes in isolated branch

Source branch: `sol/kdeconnect-receive-fix`, based on Task 4 documentation checkpoint `e1c0a1c3fb7e46974589ea6a415a90d6139700e9`.

- `scan_inbox(..., received_dir=None)` optionally reads a second, explicitly provided receive directory **without moving or deleting the source files**.
- The supplementary directory only considers likely KDE Connect file names: 64-digit lowercase hex IDs with `.md`, `reasonnotes-*.md`, or timestamp-like extensionless numeric names (12–16 digits), plus extensionless 64-digit IDs.
- The ordinary inbox maintains its existing `.md` behavior and also recognizes extensionless KDE Connect IDs.
- The scanner still requires exact ReasonNotes format markers and validates envelopes before accepting anything; unrelated files are ignored, symlinks are not followed, and an explicitly missing receive directory is reported as an error.
- The Qt application launcher and the sync/scan CLI expose an opt-in `--received-dir` argument. Existing applications run as before without it. This does **not** silently enable scanning the user's Downloads folder.

To use the opt-in receive folder in a **future owner-approved** Fedora test launch, append the following argument to the existing `reasonnotes.py` invocation:

```sh
--received-dir /home/reason/Downloads
```

For isolated CLI replay with a fresh disposable repository and an explicit incoming folder, use:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=src python3 -m reasonnotes_sync \
  --root /path/to/disposable/repository scan \
  --inbox /path/to/disposable/inbox \
  --received-dir /path/to/disposable/Downloads \
  --local-device linux
```

Never point an acceptance run at real note repositories without the owner's specific approval.

## Verification

- Nine new receive-path tests cover the correct additional folder, CLI and sync wiring, extensionless timestamps, hash-named revisions, receipts, unrelated Markdown/images, harmless unknown extensionless files, missing directories, duplicate sources, and source preservation.
- Full Fedora/Python unit suite: **150/150 passed**, no skipped or failed tests. Command: `QT_QPA_PLATFORM=offscreen PYTHONPATH=src PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -q`.
- Copies of the three actual received files were replayed through the CLI into a `mktemp` repository: **three imported, three receipts queued, zero other errors**. The note graph retained the root head and marked both children as pending due to missing parents.
- Neither the original Downloads files nor the real Fedora/Android repositories were modified.
- No KDE Connect file transfers, phone writes, live desktop launches, or actual receipt exchanges occurred during this isolated fix.

## Still unresolved

The exact reason the original Android share request yielded **only three observed files rather than eleven requested revisions** is **unknown**. The original Android code reports successful share-activity launch, not completion of each transfer. The available read-only logs did not establish why the other eight files were not present. Do not infer eight failed transfers from the request count alone, and do not change sender behavior speculatively.

Before claiming full Milestone 2 receipt synchronization, run a **separately authorized controlled test** with one new disposable note, verify transfer on Fedora, parse/import, receipt request and acceptance on Android, and compare revision/head IDs. Preserve original Android test notes and Fedora data. Only after that should a sender-side change be proposed if transfer loss is reproduced.

White/Dark/LiquidGlass, styling corrections, and user data migration remain outside this isolated receiving fix.
