# ReasonNotes Milestone 1: KDE Connect Feasibility Design

## Purpose

Milestone 1 determines whether KDE Connect can serve as the dependable local-network transport for an account-free, offline-first notes application on Fedora Linux and Android. The milestone will build only the transport and synchronization probes required to make that decision. It will not build the full notes interface or commit ReasonNotes to Flutter.

Success means that a user can edit notes independently on both devices, exchange changes over the LAN with no per-file file-manager work, and recover safely from duplicate, interrupted, deleted, or conflicting revisions. A build or source-level integration is not sufficient evidence: Android behavior must be tested on the POCO X6 5G before the KDE Connect path is called usable.

## Starting evidence

The following October 12, 2026 evidence is accepted as the starting point:

- The repository and neutral `main` branch both started at `54eb64188d08d70aaedc87534db488b72684fdaa`.
- Linux has KDE Connect CLI 26.08.1.
- The POCO X6 5G has KDE Connect device ID `52548cf9710c43cab2fa73ed4671aea9` and is paired and reachable over the LAN.
- The user previously transferred `kdeconnect-probe.md` in both directions. The returned SHA-256 was `b81b7052e3908a826da01355b2665559c18089e56ef3d1f2e604973cdc92007a`.
- That prior transfer proves only manual, uncorrupted file delivery. It does not prove application integration or synchronization.
- The installed Linux share plugin exposes D-Bus methods `shareUrl`, `shareUrls`, `shareText`, and `openFile`, plus a `shareReceived(url)` signal emitted after a successful incoming transfer.
- Current upstream KDE Connect Android source exposes an `ACTION_SEND`/`ACTION_SEND_MULTIPLE` activity and accepts a `deviceId` extra that appears to send directly to a reachable paired device. This is static evidence until verified against the installed phone application.
- KDE Connect Android can receive into a user-selected Storage Access Framework directory with a persisted grant. It writes into the final destination name, auto-renames collisions, deletes a file when the received byte count is wrong, and does not expose a documented third-party completion callback.
- No Flutter, Dart, Android SDK, Gradle, Kotlin compiler, or ADB tool is currently available on the Fedora host. A project-local or disposable Android build toolchain may be acquired only as needed for this probe.

## Approaches considered

### 1. KDE Connect as a file transport

This is the selected probe. ReasonNotes owns note revision semantics and treats KDE Connect only as an encrypted LAN file courier. Linux can automate sends and observe successful receives through the installed D-Bus interface. Android can grant both applications access to a dedicated exchange directory and can initiate a foreground share through KDE Connect's exported activity.

The principal risk is Android interaction: the intent may require an unexpected chooser, foreground transition, or repeated user action on the installed version. Incoming discovery is also limited to scanning a granted directory when ReasonNotes runs; KDE Connect does not call back into ReasonNotes.

### 2. Embed or reimplement the KDE Connect protocol

Rejected for Milestone 1. Pairing, certificate management, protocol compatibility, and background lifecycle behavior would turn the probe into a second KDE Connect implementation. It would not be the smallest way to evaluate KDE Connect and would create a maintenance burden disproportionate to a notes application.

### 3. Syncthing or a built-in ReasonNotes LAN protocol

These remain fallbacks. Syncthing is appropriate if a maintained Android client can mirror a dedicated directory reliably and the additional companion application is acceptable. A built-in protocol offers the cleanest product workflow and acknowledgements but requires discovery, authentication, transfer, retry, and background execution work owned by ReasonNotes.

Milestone 1 will recommend one of these only if the selected KDE Connect probe fails the usability or reliability criteria below.

## Prototype architecture

### Safe revision exchange core

A small Python package and CLI will model synchronization independently of KDE Connect. Python is used for the host-side feasibility engine because it is already available, requires no project framework decision, and makes the safety behavior executable and testable.

Each transferred artifact is an immutable, readable Markdown revision with a strict front matter block and a Markdown body. Required fields are:

- format version;
- note UUID;
- revision SHA-256;
- zero or one parent revision SHA-256;
- originating device ID;
- UTC modification timestamp;
- deletion flag;
- body SHA-256.

The revision filename contains the note and revision IDs but is never trusted for identity. Import validates the front matter, UUIDs, timestamps, revision hash, and body hash before changing local state. A partial or corrupt file therefore remains rejected even if KDE Connect created its final filename before finishing the payload.

Local state consists of:

- a user-readable `notes/` working view;
- an immutable `history/` archive of every validated revision and tombstone;
- a persistent `outbox/` of revisions available for retry;
- an atomic state index recording the current heads and seen revisions;
- a visible `conflicts/` area and conflict report.

State-index writes use write-then-rename in the same directory. Existing history is never deleted as part of import. The prototype may compact nothing.

### Merge rules

The importer applies these deterministic rules:

1. A known revision ID is a duplicate and produces no state change.
2. A revision whose parent is the current head is a fast-forward.
3. A revision whose current head is its recorded ancestor is also a fast-forward after missing intermediate revisions have been imported.
4. Two revisions descending from the same older revision are a conflict. Both immutable revisions are preserved, the existing working note is not silently replaced, and a conflict copy/report is created.
5. A tombstone is a revision, not physical history deletion. It fast-forwards only under the same ancestry rules.
6. A deletion concurrent with an edit is a conflict and preserves the edited content.
7. An unknown-parent revision is retained in history as pending until its ancestry arrives; it does not replace the working note.

This is revision exchange, not a text merge algorithm. Manual conflict resolution creates a new revision with an explicit chosen parent and preserves the conflicting inputs.

### Linux adapter

The Linux probe will:

- invoke the installed KDE Connect share interface for selected outbox revisions;
- accept the device ID explicitly and fail closed when the device is unavailable;
- listen for the installed `shareReceived(url)` D-Bus signal while running;
- import only files with the ReasonNotes format marker;
- rescan a configured inbox on startup to recover signals missed while the app was stopped;
- expose a one-shot scan command for deterministic testing;
- keep revisions in the outbox because KDE Connect delivery is not an application-level acknowledgement.

The adapter will not rewrite KDE Connect configuration. The live test can use the existing Linux Downloads directory as the configured inbox while filtering strictly for valid ReasonNotes envelopes.

### Android transport probe

A minimal native Android application will test platform integration without building the product UI. It will provide only:

- a one-time `ACTION_OPEN_DOCUMENT_TREE` flow for a dedicated `ReasonNotes Exchange` child directory;
- persisted read/write URI permission;
- a scan/import report when the activity starts or resumes;
- creation of one test Markdown revision;
- a user-triggered `Sync now` action that shares pending revisions with KDE Connect using a content URI and temporary read permission;
- an explicit KDE Connect share activity target and the known `deviceId`, with a generic Android chooser fallback if direct targeting is rejected;
- clear status showing that KDE Connect delivery is requested but not remotely acknowledged.

The probe will not claim continuous background synchronization. Android restricts background activity launches, and KDE Connect's public integration point is an activity. Foreground, user-initiated sharing is the expected best case. Directory scanning on application start/resume satisfies “no manual file-manager operation per transfer” if the one-time grants work reliably.

The app will use a minimal native project rather than Flutter so Milestone 1 tests Android intents, URI grants, and storage behavior directly. This does not decide the framework for the full application.

## Transfer and recovery behavior

### Offline changes

Each device can create revisions without connectivity. Revisions remain in its persistent outbox. A later user-initiated sync sends all unacknowledged revisions; repeated sends are safe because imports are idempotent.

KDE Connect does not provide ReasonNotes-level receipt acknowledgements. Milestone 1 therefore retains sent revisions and labels delivery as requested, not confirmed. A future protocol can exchange acknowledgement or inventory envelopes, but that is outside this probe unless the lack of acknowledgement alone makes the workflow unusable.

### Duplicate delivery

KDE Connect may auto-rename a repeated filename. ReasonNotes ignores transport filenames and deduplicates on the validated revision ID, so renamed duplicates have no semantic effect.

### Interrupted transfer

An importer never trusts file appearance alone. It validates complete metadata and hashes. Invalid or partial files remain quarantined/retryable and do not alter the working view. Linux additionally prefers the post-success D-Bus signal and uses startup scans only for recovery.

### Conflicting edits and deletion

Revision ancestry detects divergent edits. Neither side wins by timestamp. Concurrent edits, or an edit concurrent with a tombstone, create a visible conflict while preserving every revision.

## Verification strategy

### Automated tests

The sync core will use test-first development for:

- creating and parsing valid Markdown revisions;
- rejecting truncation, malformed metadata, unsafe identifiers, and hash mismatches;
- idempotent duplicate import despite different filenames;
- sequential fast-forward revisions;
- out-of-order ancestry arrival;
- divergent edit conflicts;
- edit-versus-delete conflicts;
- tombstone fast-forward;
- persistence and atomic state replacement;
- two isolated device directories making offline edits and later exchanging all artifacts.

The Linux adapter will test command construction, device-unavailable behavior, URL/path filtering, and rescan recovery without sending live files during the automated suite.

The Android probe will have host-side unit tests for envelope validation where practical and a build check. Its actual intent, URI-grant, lifecycle, and OEM behavior require the physical POCO test.

### Physical-device matrix

The user will be given a short, labeled procedure using only isolated test notes:

1. Install the probe APK and grant its dedicated exchange directory.
2. Configure KDE Connect Android's share destination to the same dedicated directory once.
3. Android to Linux: create a probe revision, tap `Sync now`, observe whether KDE Connect sends directly or presents additional UI, and verify Linux imports the exact revision.
4. Linux to Android: send a probe revision, reopen/resume the Android probe without using a file manager, and verify discovery/import and hashes.
5. Repeat the same revision to verify deduplication.
6. Edit the same note offline on both sides, exchange both revisions, and verify a visible non-destructive conflict.
7. Exercise a tombstone against a concurrent edit.
8. Interrupt one larger transfer if practical and verify no partial revision enters the working view.
9. Attempt Android send while the computer is unreachable and record the interaction and retained outbox state.

Every result will be labeled as source-inspected, automated, Linux-live, user-observed Android, or still pending.

## Decision criteria

Recommend KDE Connect when all of the following hold on the actual phone:

- one-time folder setup is sufficient;
- incoming revisions are discovered by opening/resuming ReasonNotes, without per-file file-manager import;
- `Sync now` initiates outgoing transfer with no repeated file selection and no more than a brief acceptable KDE Connect foreground transition;
- offline retry and duplicate delivery do not lose or overwrite notes;
- conflicts and deletion races remain visible and recoverable;
- the remaining lack of immediate remote acknowledgement is acceptable for a manual sync action.

Recommend Syncthing when dependable folder mirroring is available through a maintained Android client and its companion-app/background requirements are preferable to KDE Connect's repeated foreground interaction.

Recommend a built-in LAN protocol when KDE Connect direct targeting is unavailable or unstable, Android integration requires repeated choosers, receipt acknowledgement is necessary for a trustworthy workflow, or a companion synchronization application is not acceptable.

## Scope boundaries

Milestone 1 will not add accounts, cloud services, Google dependencies, telemetry, a full editor, search, a polished interface, background-service workarounds, or a production merge UI. It will not modify unrelated files or KDE Connect configuration automatically. All live transfers use isolated test notes. No result from a simulator, build, source inspection, or prior manual copy will be reported as Android application runtime proof.

## Research references

- KDE Connect Android share activity and direct `deviceId` path: <https://github.com/KDE/kdeconnect-android/blob/master/src/main/java/org/kde/kdeconnect/plugins/share/ShareActivity.kt>
- KDE Connect Android incoming-file behavior: <https://github.com/KDE/kdeconnect-android/blob/master/src/main/java/org/kde/kdeconnect/plugins/share/CompositeReceiveFileJob.java>
- KDE Connect Android persisted destination grant: <https://github.com/KDE/kdeconnect-android/blob/master/src/main/java/org/kde/kdeconnect/plugins/share/ShareSettingsFragment.java>
- KDE Connect Linux share plugin and completion signal: <https://github.com/KDE/kdeconnect-kde/blob/master/plugins/share/shareplugin.cpp>
- Android Storage Access Framework directory access: <https://developer.android.com/training/data-storage/shared/documents-files>
- Android background activity launch restrictions: <https://developer.android.com/guide/components/activities/secure-bal>

## Deliverables

- tested safe-revision core and Linux KDE Connect adapter;
- minimal Android integration probe source and APK when the available toolchain permits it;
- reproducible test fixtures and physical-phone procedure;
- feasibility report with evidence labels, limitations, risks, and recommendation;
- actionable Milestone 2 plan tied to the recommendation;
- updated `PROJECT_STATUS.md` and `handoffs/FORGE_TO_QWEN.md`;
- committed `forge/kdeconnect-feasibility` work pushed to the neutral remote with its remote SHA verified.
