# Android receipt sender repair — 2026-10-23

## Physical failure and root cause

The first isolated Fedora-to-Android JSON v1 note import completed durably and
Android queued receipt
`5da1a04a7ab0c233859dae6231d9c79871263ea9423e654d7607872bafb9cc66`.
Android then generated the correct receipt-only 507-byte JSON v1 carrier, but
KDE Connect logged a `SecurityException` while querying the non-exported
ReasonNotes `FileProvider` URI. The intent's read flag and `ClipData` grant were
scoped to KDE Connect's launched share activity; KDE Connect delegated URI
reading to asynchronous transfer code where that grant was not effective.

## Repair

`KdeConnectSender.request()` now grants `org.kde.kdeconnect_tp` explicit,
read-only access to each outgoing content URI immediately before launching the
explicit KDE Connect share activity. The package grant is temporary: it is
revoked after five minutes, or immediately if explicit activity launch fails.
The generic chooser fallback continues to use Android's intent/`ClipData`
grant and does not retain the KDE-specific grant.

The provider remains `android:exported="false"` with
`android:grantUriPermissions="true"`. No filesystem permission, protocol,
receipt, queue, or ledger behavior changed.

## Focused verification

- The new source-contract regression was observed failing before the repair.
- `SyncActivityContractTest`, `SyncCoordinatorTest`, `TransportBundleTest`,
  and `ReceiptStoreTest`: 36 tests passed, zero failures.
- `assembleDebug assembleDebugAndroidTest`: successful. The instrumentation
  test APK compiled but was not installed or executed.
- Packaged manifest inspection confirms the `FileProvider` remains
  non-exported and grant-enabled.

## Candidate

- APK:
  `/home/reason/Temporary/ReasonNotes-JSON-v1-Candidates/android-receipt-grant-repair/ReasonNotes-Android-JSON-v1-receipt-grant.apk`
- SHA-256:
  `e0e1f8e6eb2c8d184f90aaa59f9bfc0625a1aa3a6aa5ae9cbbda56c7b5ea81ec`
- Package: `org.reasonnotes.probe`
- Version: `0.1` (`versionCode 1`)
- Signing certificate SHA-256:
  `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147`

No APK was installed. The existing Android data, pending receipt, and attempt
history were not reset or modified by this source/build milestone. Remaining
acceptance is a separately authorized in-place installation followed by one
controlled retry of the existing receipt and Fedora durable-confirmation
verification.
