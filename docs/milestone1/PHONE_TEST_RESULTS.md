# POCO X6 5G Acceptance Results

Tested on 2026-10-12 with a POCO X6 5G (`23122PCD1G`), Android 16,
KDE Connect Android 1.35.17, and KDE Connect Linux 26.08.1.

## Tested artifact

- Source commit: `7d9c597ee68720b7ac0f547f2f320123990a24ed`
- APK: `/home/reason/Codex-Projects/ReasonNotes-artifacts/ReasonNotes-Probe-multinote-7d9c597-31002579.apk`
- APK SHA-256: `310025791599f0c226cfaef5f59f6d143d2cdf23578b3436927165d60936d03e`
- Package: `org.reasonnotes.probe`, version 0.1
- Android target for outgoing shares: Linux peer
  `254997f28f11499d80928fe8a109749e`
- Linux target for outgoing shares: POCO peer
  `52548cf9710c43cab2fa73ed4671aea9`

The earlier frozen APK remains unchanged at
`/home/reason/Codex-Projects/ReasonNotes-artifacts/ReasonNotes-Probe-original-ef19db85.apk`
with SHA-256
`ef19db85b723b6e495759cb5cb48313157801f20fc3f4260b4f1703d612f1c90`.

## Procedure results

| Step | Result | Evidence |
| --- | --- | --- |
| P0 | PASS | ADB authorized the physical POCO. |
| P1 | PASS with installation workaround | HyperOS rejected `adb install -r` with `INSTALL_FAILED_USER_RESTRICTED`; the user installed the hash-verified APK through the file manager. |
| P2 | PASS after focused UI fix | The original first two controls were underneath the Android 16 action bar. The corrected hierarchy places the first button at y=482 while the action bar ends at y=290. The user granted only `ReasonNotes Exchange`, and the persisted tree URI reopened successfully. |
| P3 | PASS by direct runtime evidence; instrumentation runner not installed | A valid envelope imported durably, a truncated envelope was quarantined, unrelated text was ignored, KDE Connect's exported Share activity resolved, and the persisted tree reopened. HyperOS prevented installing the test-only APK, so this is equivalent direct evidence rather than an instrumentation-runner result. |
| P4 | PASS | Linux requested one KDE Connect share. The exact envelope arrived automatically in the configured directory, with no chooser or file-manager operation. Android imported and displayed revision `36f02a00c1c7627d22aab887d81619df97ca3798ec8a4cf80feb24253a5aee73`; its body SHA-256 was `1397c0323dba18ba2c1cce5d16fd1cc682a44c90df421cbb4746128124aaba61`. |
| P5 | PASS | Android created and shared revision `7c1792c1475a12cb755982e69fd448871ed3d348a5a7823b8a9ef1a352ce0a83`. Linux separately observed KDE Connect completion and durable ReasonNotes import. Its body SHA-256 was `afcc8f1915097b0cd73414a4c81353b3c1420d88f1eaa463e59134f8b55db033`. Complete head IDs and body hashes then matched on both devices. |
| P6 | PASS for duplicate, offline retry, conflicts, deletion, out-of-order, and late-delivery cases | Details below. A deliberately interrupted large transfer was not attempted. |

## P6 safety evidence

- A renamed duplicate was imported as a duplicate: history and heads did not
  multiply. Default sync did not resend; an explicit retry did.
- With Wi-Fi disabled, Linux reported the target unavailable without consuming
  an attempt. Android displayed KDE Connect's `Device not reachable`
  notification. Two explicit user retries advanced the same retained revision
  to the bounded third-attempt `needs-attention` state; no import was claimed.
- A same-note Linux/Android conflict displayed both device labels and both
  readable bodies. Skip left the state index and every history file unchanged.
- Keep Linux created resolution
  `92226ea685753b012ef439698abc20429adf1d7b76f3e4f1763123ca8c2d142b`
  with both competing heads as parents. Android converged to the Linux body and
  retained all four revisions.
- Keep Android created resolution
  `bb7b9273708b15972b3f17b24940659316538de7da38ab8a2a578de59e92fbd1`.
  Android converged to `Keep Android Android version` and retained the base,
  both branches, and the resolution.
- Edit versus deletion was resolved in both semantic directions. Resolution
  `9feecb29bcf8e5af0f4e417c88dbcef9cffa649860e8cbd21d195bc51cb87513`
  preserved the edited body. Resolution
  `d812b322eb0ccbea1895a13f3a812341263caa22205e98502671d1ba04901a6a`
  preserved the tombstone with an empty body. Both resolutions name both
  competing heads as parents, and all four revisions remain in history.
- A stale resolution plus concurrent third head remained a conflict with heads
  `acded3936c03273e8a44b944f93b060910764b0549ccd6455b883d1771ad93bc`
  and `2950b55886e9e5c7401fa4efc15deeb2d8809663ba4723f9d903b6953a92a825`.
- A resolution and both children delivered before their missing base stayed
  pending. Importing the base later activated resolution
  `9af99f6c5676a6ad096bde688695d220fab298ffb13f5c3a6c59f1c11fd5d9fb`
  as the sole head without resurrecting an ancestor.

## Observed interaction model

One-time setup required installing the APK, granting the dedicated SAF tree,
and selecting that same directory as KDE Connect Android's receive destination.
After setup, direct targeting opened no chooser. A complete exchange required
one Linux sync invocation and one Android **Sync now** tap while the Linux
listener was active. Android scanned incoming files before sending its own
outstanding revisions. Offline work remained local until the peer returned.

KDE Connect did not provide a ReasonNotes import acknowledgement. A transfer
request, KDE Connect completion, and durable ReasonNotes import were observed as
three distinct events. Consequently, the sender can retain an already imported
revision as unconfirmed. Default sync does not repeatedly resend it; explicit
retry is capped and observable. Milestone 2 should add a small receipt envelope
before presenting delivery state as confirmed to users.

## Not run

- The test-only instrumentation APK was not installed because HyperOS blocked
  the ADB installation path. Direct physical checks covered its acceptance
  assertions.
- A deliberately interrupted large in-flight KDE Connect transfer was not
  attempted. Partial-envelope quarantine, offline transfer failure, immutable
  source history, and restart reconciliation were verified separately, but this
  exact transport interruption remains unobserved.
