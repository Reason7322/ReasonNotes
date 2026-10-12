# Forge -> Qwen Handoff

## Checkpoint

- Sender branch: `forge/kdeconnect-feasibility`
- Received Qwen checkpoint:
  `73710b088fb0d8f167a054916fef57ffc78a233c`
- Latest source checkpoint before this handoff documentation:
  `0482711e0a4db132c7608a98358fa0b630806e47`
- Verify the final pushed remote SHA before checkout; this file cannot contain
  the hash of its own commit.

## Delta

Forge fixed the Android self-targeting bug, corrected the Android 16 action-bar
overlap, made the probe display every imported note, and fixed CLI resolutions
so `--target-device android` registers the resolution in the outbox.

Physical POCO acceptance P0–P6 is complete. Both directions transferred through
KDE Connect without a chooser or per-file file-manager operation after setup.
Linux and Android converged by exact head ID and body hash. Physical safety
fixtures verified duplicate delivery, offline retry limits, Skip, Keep Linux,
Keep Android, edit/deletion choices, stale resolution plus a third head, and a
late ancestor. Full evidence is in
`docs/milestone1/PHONE_TEST_RESULTS.md`.

## Artifact

- Source commit: `7d9c597ee68720b7ac0f547f2f320123990a24ed`
- APK:
  `/home/reason/Codex-Projects/ReasonNotes-artifacts/ReasonNotes-Probe-multinote-7d9c597-31002579.apk`
- SHA-256:
  `310025791599f0c226cfaef5f59f6d143d2cdf23578b3436927165d60936d03e`

The original frozen APK was preserved unchanged.

Final verification: 61/61 Python tests and 17/17 Android host tests passed;
`assembleDebug` and `assembleDebugAndroidTest` succeeded. The rebuilt app APK
matched the physical-test artifact SHA-256 exactly.

## Evidence boundary

- The test-only instrumentation APK did not install through ADB because HyperOS
  blocks that route. Equivalent assertions were verified directly on the
  physical device.
- A deliberately interrupted large in-flight transfer was not attempted.
- KDE Connect still does not provide application-level import acknowledgement.
  The sender can safely retain an already imported revision as unconfirmed;
  default sync does not resend it and explicit retry is capped.
- Do not reinterpret a transfer request or KDE completion as ReasonNotes import.

## Recommendation and next action

Continue with KDE Connect. The next plan is
`docs/superpowers/plans/2026-10-12-milestone2-minimal-notes-slice.md`.
Begin with the small receipt envelope, then build only the minimal CRUD/search
shell around the proven Python and native Android cores. Do not restart the
Milestone 1 investigation or repeat the completed phone matrix without a
specific regression.
