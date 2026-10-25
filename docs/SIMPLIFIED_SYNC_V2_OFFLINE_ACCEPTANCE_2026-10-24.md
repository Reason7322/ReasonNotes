# Simplified Sync v2 offline acceptance — Android

## Scope and source

Task 8 validates the approved current-state protocol offline. The Android
runtime source is `forge/android-json-v1-integration` at
`100b3e0ca8d0c3c9371a6e24a5fde33a3931753b`. No APK was installed, no app was
launched, no personal repository was migrated, and no KDE Connect transfer was
requested.

## Cross-platform fixtures

`CrossPlatformV2AcceptanceTest` and Fedora's
`tests/test_sync_v2_cross_platform.py` reproduce one deterministic eight-file
transcript with their native implementations. All carrier bytes and SHA-256
names agree across Java and Python. The transcript covers Android create,
independent Android/Fedora edits, a Fedora resolution containing both parents,
Android deletion, exact Fedora acknowledgment, a second Android create, and a
Fedora deletion.

The Java test imports Fedora carriers through `CurrentTransport`, preserves two
complete conflict candidates until choice, converges on the chosen body, clears
only the exactly acknowledged pending deletion, rejects stale resurrection,
and verifies deleted titles/bodies are absent from durable files. Existing v2
tests additionally cover duplicate delivery, restart recovery, coalesced
pending changes, independent-note progress, unreachable-peer retry, and exact
acknowledgment convergence.

## Verification and artifact

Command (environment paths supplied only for this invocation):

```sh
env JAVA_HOME=/home/reason/Temporary/ReasonNotesToolchain/install/jdk-17.0.21+9 \
  ANDROID_HOME=/home/reason/Temporary/ChatGPTProjects/ReasonNotes/toolchain/Android/install/android-sdk \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Result: 201 tests in 40 suites, zero failures, errors, or skips; debug APK build
successful. The host Java `25.0.4.1` cannot be parsed by this project's Kotlin
tooling, so the existing project JDK 17 was used without changing the system.

Candidate:

- Path: `/home/reason/Temporary/ReasonNotes-Sync-v2-Candidates/android/ReasonNotes-Android-Sync-v2-100b3e0.apk`
- Size: 2,528,287 bytes
- SHA-256: `eb0da72c4c6f281b4b2d3e4377f5b735cff3560330f5105956d7dd332a8519dd`
- Package: `org.reasonnotes.probe`, version code `1`, version name `0.1`
- Signing: APK Signature Scheme v2; certificate SHA-256
  `d30a8083a422ed86f1b177b3d5c56d1d51db1a926f8c3d15807609c860eaa147`
- Launcher: `res/drawable/reasonnotes_launcher.xml` is packaged and referenced.

## Migration and remaining acceptance

On first application open, this APK detects the legacy `probe-repository`,
builds and reopens a staged `reasonnotes-v2` store, validates live notes,
conflicts, deletions, and pending state, atomically activates it, and then
permanently erases legacy history. That migration is intentionally one-way and
was not exercised against personal data in Task 8. Downgrading the APK alone
would not restore erased legacy history. A fresh verified phone-data recovery
point and explicit installation/launch authorization are therefore required
before physical acceptance.

Still unverified physically: migration on the POCO, save/edit performance,
Fedora-to-Android and Android-to-Fedora transfer, durable acknowledgment in both
directions, visible conflict choice, permanent deletion in both directions,
retry after disconnection, and restart persistence.
