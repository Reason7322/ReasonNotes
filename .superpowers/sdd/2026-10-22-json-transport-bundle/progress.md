# SDD ledger — plan: docs/superpowers/plans/2026-10-22-json-transport-bundle.md
Pre-flight: Task 1 TransportBundle -> Task 2 receiver: carrier/items align.
Pre-flight: Task 1 TransportBundle -> Task 3 sender: carrier creation aligns.
Task 1: Ruling: use cached Gson 2.10.1 as the Android and JVM JSON implementation instead of test-only org.json — one parser implementation is simpler and avoids Android stub behavior in local tests — cost if wrong: adds about one small packaged dependency.

Task 1: complete (commits 86083de..dd7a3a3, tests: env ANDROID_HOME=/home/reason/Temporary/ChatGPTProjects/ReasonNotes/toolchain/Android/install/android-sdk JAVA_HOME=/home/reason/Temporary/ChatGPTProjects/ReasonNotes/toolchain/Android/install/jdk-17.0.20.1+1 android-probe/gradlew -p android-probe testDebugUnitTest --tests org.reasonnotes.probe.TransportBundleTest --no-daemon --console=plain → 20 actionable tasks: 20 up-to-date)
Task 2: complete (tests: ExchangeDirectoryTest → 4 tests, failures=0; bundle parsing validates the full carrier before applying any member, legacy .md scan remains unchanged)
Task 3: complete (full testDebugUnitTest + assembleDebug succeeded; Android persists one canonical JSON carrier and submits one content URI through KDE Connect; FileProvider exposes only the transport outbox)
