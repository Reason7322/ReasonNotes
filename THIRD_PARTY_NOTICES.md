# Third-party notices

The MIT license in this repository applies only to ReasonNotes project-owned
source. It does not relicense dependencies, generated wrappers, platform SDKs,
or assets owned by others.

The Android application uses:

- AndroidX Core 1.15.0, AndroidX DocumentFile 1.0.1, AndroidX Test JUnit 1.2.1,
  and AndroidX Test Runner 1.6.2 — Apache License 2.0.
- Gson 2.10.1 — Apache License 2.0.
- JUnit 4.13.2 for tests — Eclipse Public License 1.0.
- The Gradle wrapper scripts — Apache License 2.0; their original headers are
  retained in `android-probe/gradlew` and `android-probe/gradlew.bat`.
- Android platform APIs — distributed under the Android SDK terms and upstream
  component licenses.

Future APK builds carry the complete Apache License 2.0 and this dependency
notice in `android-probe/app/src/main/assets/licenses/`. The full Apache text is
[`android-probe/app/src/main/assets/licenses/LICENSE-Apache-2.0.txt`](android-probe/app/src/main/assets/licenses/LICENSE-Apache-2.0.txt).
The accepted v0.1.0 APK predates that packaging correction and remains
byte-identical to the owner-accepted artifact; these public source notices are
the corresponding distribution notice for that unchanged binary.

The retained Qt Quick prototype contains Lucide-derived icons. Its upstream
ISC/MIT notices are preserved verbatim in
`linux-app/qml/icons/lucide/LICENSE`; those files are not relicensed by the
project MIT license.

The authoritative Fedora application depends on Qt/PySide6, KDE Connect,
Kvantum, and Wayland as separately distributed system components. See the
[Fedora repository notices](https://github.com/Reason7322/ReasonNotesKvantum/blob/main/THIRD_PARTY_NOTICES.md).
