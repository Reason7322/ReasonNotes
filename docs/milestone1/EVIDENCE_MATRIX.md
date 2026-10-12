# Milestone 1 Evidence Matrix

| Claim | Evidence | Status |
| --- | --- | --- |
| Manual KDE Connect files previously crossed both directions without corruption | Prior hardware SHA-256 verification | source-inspected / accepted starting evidence |
| Canonical envelope and Python graph/storage semantics | 61 Python `unittest` tests | automated — passed |
| Two isolated repositories converge after explicit multi-parent selection | Two-device tests and disposable demo | automated — passed |
| Requests, KDE completion, and ReasonNotes import are distinct | Python and Java ledger tests | automated — passed |
| Linux scan-before-send does not resend by default | Fake-adapter orchestration tests | automated — passed |
| Java canonical vector matches Python | Python and Android host tests | automated — passed |
| Android repository, retry ledger, SAF scanner, and sender compile | Android host tests; app and instrumentation APK builds | automated — passed |
| Linux sees paired POCO `52548cf9710c43cab2fa73ed4671aea9` | Live `kdeconnect-cli --list-available --id-only` on 2026-10-12 | Linux-live — passed |
| Corrected APK installs and launches on POCO | Manual file-manager install plus ADB package/runtime inspection | Android-live — passed with HyperOS workaround |
| Persisted dedicated SAF tree works on POCO | User grant, reopen/resume scan, direct repository inspection | Android-live — passed |
| Installed KDE Connect resolves the explicit Android share activity | Android package/activity resolution and successful direct outgoing transfer | Android-live — passed |
| Android direct target bypasses repeated file selection/chooser | Android `Sync now` sent to Linux peer without chooser | Android-user-observed / Linux-live — passed |
| Incoming Android files are discovered without file-manager work | Configured KDE receive directory plus probe scan/import | Android-live — passed |
| Actual bidirectional exchange converges | P4/P5 exact complete head-ID and body-hash comparison | Android-live / Linux-live — passed |
| Duplicate, offline retry, conflicts, deletion, out-of-order, and late delivery are safe on hardware | P6 physical matrix and private repository inspection | Android-live / Linux-live — passed |
| Deliberately interrupted large in-flight transfer | Not attempted; partial-file quarantine and restart reconciliation verified separately | pending / non-gating |
| Sender knows that remote ReasonNotes imported a revision | KDE Connect exposes no application receipt; bounded unconfirmed state observed | known limitation — Milestone 2 receipt planned |

## Frozen toolchain evidence

- Temurin JDK 17.0.20.1 archive SHA-256: `3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e`
- Gradle 8.9 binary archive SHA-256: `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`
- Gradle 8.9 wrapper JAR SHA-256: `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`
- Android command-line tools archive SHA-256: `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`
- SDK platform 35, build-tools 35.0.0, platform-tools; AGP also acquired build-tools 34.0.0 as a compatibility requirement.
- Toolchain and caches are under `/home/reason/Temporary/ReasonNotesToolchain` and are not committed.

## Current evidence boundary

Physical results, exact revision IDs, hashes, interaction counts, and exclusions
are recorded in `PHONE_TEST_RESULTS.md`. The test-only instrumentation runner
was not installed, and a deliberately interrupted large in-flight transfer was
not run. Neither limitation invalidates the observed two-way exchange or the
tested conflict/data-retention behavior. KDE Connect completion still does not
prove remote ReasonNotes import without a future receipt envelope.

