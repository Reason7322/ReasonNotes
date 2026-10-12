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
- zero or more unique parent revision SHA-256 values, sorted for canonical serialization;
- originating device ID;
- UTC modification timestamp;
- deletion flag;
- body SHA-256.

The revision SHA-256 covers the canonical metadata, sorted parent set, and body. The revision filename contains the note and revision IDs but is never trusted for identity. Import validates the front matter, UUIDs, timestamps, revision hash, and body hash before changing local state. A partial or corrupt file therefore remains rejected even if KDE Connect created its final filename before finishing the payload.

Local state consists of:

- a user-readable `notes/` working view;
- an immutable `history/` archive of every validated revision and tombstone;
- a persistent outbox delivery ledger that references revisions in history rather than owning their sole recoverable copies;
- an atomic state index recording the current heads and seen revisions;
- a visible `conflicts/` area and conflict report.

Immutable history is the source of truth. A durable update writes and syncs the validated history artifact before atomically replacing the state index, then atomically rematerializes the working view. On startup the application can rebuild the revision graph, state index, conflict records, and working view from history. An interruption at any step therefore cannot leave an index or visible note that refers to missing history. Existing history is never deleted as part of import. The prototype performs no compaction.

### Merge rules

The importer derives the maximal heads of each complete revision graph: a head is a revision that is not an ancestor of another complete revision. It applies these deterministic rules:

1. A known revision ID is a duplicate and produces no state change.
2. A note with one complete maximal head is converged. Its working view follows that head, including a tombstone.
3. A note with more than one complete maximal head is conflicted. Every head remains available with its originating-device label and readable body preview. The application does not replace the prior working note silently.
4. A revision with any unknown parent is retained in history as pending. It cannot become a head or change the working view until every parent arrives.
5. A concurrent deletion and edit produce separate maximal heads and therefore a conflict; edited content remains recoverable.
6. Duplicate or out-of-order ancestors delivered after a resolution cannot become heads because they are already ancestors of the resolution revision.

Conflict resolution is an explicit user operation. The eventual desktop interface presents Linux and Android versions side by side; Android presents them vertically. Both show device labels and readable previews. The choices are **Keep Linux version**, **Keep Android version**, and **Skip for now**. Skipping leaves the graph and conflict unchanged.

Choosing a version creates a new resolution revision whose body and deletion state exactly match the selected head and whose parent set contains every maximal head observed in that conflict. The unselected revisions remain in immutable history. Once both devices import the resolution revision and its parents, it becomes the single maximal head and both working views converge. A resolution made from stale knowledge acknowledges only the heads it saw; any concurrent unacknowledged head remains maximal and the note correctly stays conflicted.

Milestone 1 implements this deterministic selection through the Python CLI and fixtures. It does not build the graphical conflict dialog or a general-purpose text merge engine.

### Linux adapter

The Linux probe will:

- invoke the installed KDE Connect share interface for selected outbox revisions;
- accept the device ID explicitly and fail closed when the device is unavailable;
- listen for the installed `shareReceived(url)` D-Bus signal while running;
- import only files with the ReasonNotes format marker;
- rescan a configured inbox on startup to recover signals missed while the app was stopped;
- expose a one-shot scan command for deterministic testing;
- record transfer requests in a per-target delivery ledger without calling them completed or imported;
- keep every revision in immutable history even when it is no longer eligible for an automatic resend.

The adapter will not rewrite KDE Connect configuration. The live test can use the existing Linux Downloads directory as the configured inbox while filtering strictly for valid ReasonNotes envelopes.

### Android transport probe

A minimal native Android application will test platform integration without building the product UI. It will provide only:

- a one-time `ACTION_OPEN_DOCUMENT_TREE` flow for a dedicated `ReasonNotes Exchange` child directory;
- persisted read/write URI permission;
- a scan/import report when the activity starts or resumes;
- creation of one test Markdown revision;
- a user-triggered `Sync now` action that first scans/imports the granted exchange directory and then shares eligible local revisions with KDE Connect using content URIs and temporary read permission;
- an explicit KDE Connect share activity target and the known `deviceId`, with a generic Android chooser fallback if direct targeting is rejected;
- a minimal current-note/status view capable of demonstrating non-conflicting convergence after import;
- clear status showing transfer requested, KDE Connect completion when externally observable, and local import as three separate events.

The probe will not claim continuous background synchronization. Android restricts background activity launches, and KDE Connect's public integration point is an activity. Foreground, user-initiated sharing is the expected best case. Directory scanning on application start/resume satisfies “no manual file-manager operation per transfer” if the one-time grants work reliably.

The app will use a minimal native project rather than Flutter so Milestone 1 tests Android intents, URI grants, and storage behavior directly. This does not decide the framework for the full application.

## Transfer and recovery behavior

### Offline changes

Each device can create revisions without connectivity. New revisions start as `pending` in a per-target delivery ledger and remain in immutable history. A sync action makes at most one request per eligible revision and target during that run. If the target is known to be unavailable, no request is recorded and the revision remains pending.

After the KDE Connect IPC call or Android activity launch succeeds, the ledger records `transfer-requested`, the attempt count, and the request time. This means only that KDE Connect accepted the request. The normal sync path does not resend `transfer-requested` entries indefinitely. The user may explicitly request a retry of unconfirmed entries; each retry makes one additional request, with three requests per revision and target before the entry becomes `needs-attention`. A targeted force-retry remains available and is always explicit and observable.

`kdeconnect-transfer-complete` is recorded only where completion is actually observed: for example, the receiving Linux KDE Connect `shareReceived(url)` signal or a labeled physical-test observation of KDE Connect Android's completed-transfer notification. `reasonnotes-imported` is recorded only by the receiving ReasonNotes instance after validation and durable import. These are separate logs on separate devices unless a future acknowledgement is exchanged.

Milestone 1 does not add a ReasonNotes acknowledgement protocol. Consequently, a sender may retain `transfer-requested` entries after the receiver has imported them. The revision remains recoverable in history, but default sync does not repeatedly transfer it. Physical testing will determine whether the resulting explicit retry and unconfirmed status are acceptable or whether Milestone 2 needs small receipt/inventory envelopes.

### Duplicate delivery

KDE Connect may auto-rename a repeated filename. ReasonNotes ignores transport filenames and deduplicates on the validated revision ID, so renamed duplicates have no semantic effect.

### Interrupted transfer

An importer never trusts file appearance alone. It validates complete metadata and hashes. Invalid or partial files remain quarantined/retryable and do not alter the working view. Linux additionally prefers the post-success D-Bus signal and uses startup scans only for recovery.

### Conflicting edits and deletion

Revision ancestry detects divergent edits. Neither side wins by timestamp. Concurrent edits, or an edit concurrent with a tombstone, create a visible conflict while preserving every revision. A keep selection produces a multi-parent resolution revision; skip produces no revision. Importing that resolution collapses only the heads it explicitly acknowledges.

## Complete synchronization sequence

For the feasibility prototype, synchronization is a scan-then-send action on each platform rather than a one-way push:

1. Linux `sync` scans its configured inbox, validates and imports incoming revisions, reports conflicts, and requests transfer of eligible Linux revisions to Android.
2. Android `Sync now` scans the persisted exchange directory, validates and imports Linux revisions, reports its current state, and requests transfer of eligible Android revisions to Linux through KDE Connect.
3. While the Linux listener is running, KDE Connect's successful incoming-transfer signal triggers Linux import. If it was not running, the next Linux `sync` recovers the file through its inbox scan.
4. With only non-conflicting changes, one Linux sync followed by one Android sync lets Android import the Linux changes and Linux import the Android changes. Both working views then have the same single heads.
5. With a conflict, both competing revisions must first reach one resolver. The Milestone 1 CLI shows both previews and device labels. **Skip for now** changes nothing. A keep choice creates a resolution revision acknowledging every observed head. That revision must then be sent to the other device and imported there before convergence is complete.

If either device is offline, the online device retains pending or unconfirmed delivery state and immutable history. It does not report bidirectional synchronization complete. The user retries after reachability returns. A conflict can require an additional sync round after resolution. These extra actions are explicit Milestone 1 limitations; a one-way transfer is never reported as completed bidirectional synchronization.

## Verification strategy

### Automated tests

The sync core will use test-first development for:

- creating and parsing valid Markdown revisions;
- rejecting truncation, malformed metadata, unsafe identifiers, and hash mismatches;
- idempotent duplicate import despite different filenames;
- sequential fast-forward revisions;
- out-of-order ancestry arrival;
- divergent edit conflicts;
- user selection of either conflicting device version;
- skip preserving an unresolved conflict;
- multi-parent resolution convergence on two devices;
- stale resolution leaving an unacknowledged concurrent head conflicted;
- late duplicate/ancestor delivery not resurrecting a resolved conflict;
- edit-versus-delete conflicts;
- resolving an edit-versus-delete conflict in either direction;
- tombstone fast-forward;
- interrupted history/index/working-view updates followed by reconciliation;
- bounded delivery attempts and explicit force-retry;
- distinct transfer-requested, KDE Connect completion, and ReasonNotes import records;
- two isolated device directories making offline edits and later exchanging all artifacts.

The Linux adapter will test command construction, device-unavailable behavior, URL/path filtering, and rescan recovery without sending live files during the automated suite.

The Android probe will have host-side unit tests for envelope validation where practical and a build check. Its actual intent, URI-grant, lifecycle, and OEM behavior require the physical POCO test.

### Physical-device matrix

The user will be given a short, labeled procedure using only isolated test notes:

1. Install the probe APK and grant its dedicated exchange directory.
2. Configure KDE Connect Android's share destination to the same dedicated directory once.
3. Create independent non-conflicting revisions, run Linux `sync`, then Android `Sync now`; verify Android imports Linux changes and Linux imports Android changes without file-manager operations.
4. Verify the two devices show the same current revision IDs and note bodies after the Linux listener imports the return transfer.
5. Repeat the same revision under an auto-renamed transport filename and verify deduplication.
6. Edit the same note offline on both sides, exchange both revisions, and verify both device-labeled contents remain available without a selected winner.
7. Choose each keep option in separate resettable fixtures. Verify the resulting multi-parent resolution converges on both devices and the superseded revision remains in history. Verify skip changes nothing.
8. Exercise an edit-versus-tombstone conflict and resolve it in each direction.
9. Deliver a late ancestor and duplicate after resolution; verify neither resurrects the conflict or changes the working view.
10. Interrupt one larger transfer if practical and verify no partial revision enters the working view.
11. Attempt Android send while Linux is unreachable; verify no false import/completion claim, bounded retry state, and retained history.
12. Record every required action and visible KDE Connect transition so the final recommendation reflects the actual interaction cost.

Every result will be labeled as source-inspected, automated, Linux-live, user-observed Android, or still pending.

## Decision criteria

Recommend KDE Connect when all of the following hold on the actual phone:

- one-time folder setup is sufficient;
- incoming revisions are discovered by opening/resuming ReasonNotes, without per-file file-manager import;
- `Sync now` initiates outgoing transfer with no repeated file selection and no more than a brief acceptable KDE Connect foreground transition;
- offline retry and duplicate delivery do not lose or overwrite notes;
- conflicts and deletion races offer explicit keep-Linux, keep-Android, and skip choices, with superseded revisions retained;
- the tested sequence leaves both devices on the same revision IDs and note bodies before it is called converged;
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
