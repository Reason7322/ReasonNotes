# Forge -> Qwen Handoff

## Checkpoint

- Sender branch: `forge/android-notes-shell`.
- Android implementation before this handoff document:
  `111a8984dc34eb9a1749186d3ea6e7367ada8a16`.
- Desktop counterpart: ReasonNotesKvantum `forge/reasonnotes-completion` at
  `88def77e2bfe3c6b2b92897b5d134395a761ffd3`.
- Verify the final pushed Android SHA before checkout; this document cannot
  contain its own commit hash.

## Implemented

Canonical JSON v1 batching is implemented on both platforms. A carrier keeps
each existing revision/receipt envelope byte-for-byte and validates the full
schema, hash, canonical encoding, identities, limits, and every embedded
envelope before repository mutation. New sends use one carrier/KDE Connect
request. Legacy individual `.md` remains readable. Android does not implement
ZIP. Desktop retains read-only ZIP import only because ZIP was physically used
before the JSON decision.

Android verification before physical testing: 99/99 host tests and
`assembleDebug` passed. Installed APK SHA-256:
`9179c9bfe8594b3c90d3ecc0b865b4f5cc2fcb353953e2923a69e13f0e060ed1`.
It was installed in place over USB on POCO X6 5G serial `59dffe68`; app data and
preferences were preserved. Production desktop paths were not touched.

## Physical evidence and discovered bottleneck

The exact desktop candidate sent four fixture revisions as one 2,114-byte
carrier:

`reasonnotes-bundle-v1-653e9d081325c8895b2878b5960006f2508555227a2ee6dab0b33bcaa816f9a2.json`

Desktop durable creation epoch: `1792640559.141730032`.
Phone exchange-directory epoch: `1792640560`.
Observed network delivery was approximately 0.86 seconds and transfer count
was exactly one.

Android then remained at "Scanning incoming notes and receipts" for more than
45 seconds and had not imported revision
`1f3b83b289dfa80080b72b31227fec1b51884cc97e5eb7dfd4a4458f850e3670`.
The physical phone has 646 already durable private receipt files and 365 legacy
SAF exchange files. Current startup reconciliation replays all confirmed
receipts, and exchange scanning re-opens all legacy `.md` files. The run was
stopped without claiming import/receipt/success. The desktop ledger correctly
remains `transfer-requested`.

## Intentionally red worktree state

Two tests were added and are deliberately red:

1. `DeliveryLedgerTest.settledReceiptFastPathRequiresBothLedgerAndConfirmationEvent`
   expects `settledReceiptIds()`. A receipt is settled only if its confirmed
   ledger entry has the matching `reasonnotes-confirmed` event. This preserves
   recovery after an interrupted event append.
2. `ExchangeDirectoryTest.recognizesCanonicalAndCollisionLegacyEnvelopeNames`
   expects `LegacyIdentity` plus `legacyIdentity(String)` for revision and
   receipt names, including KDE collision suffixes.

Focused Gradle compilation currently reports 11 missing-symbol errors for
only those unimplemented APIs. Do not remove or weaken the tests.

## Smallest next action

Implement the two helpers. Use `settledReceiptIds()` to avoid reparsing fully
settled private receipts in `ReceiptStore.reconcileConfirmations`. In
`ExchangeDirectory.scan`, identify content-addressed legacy filenames before
opening SAF streams; skip already stored receipts, and treat already imported
revisions idempotently while still repairing missing import evidence/return
receipt state. Do not introduce deletion of exchange files.

Then run only:

```sh
ANDROID_HOME=/home/reason/Temporary/ChatGPTProjects/ReasonNotes/toolchain/Android/install/android-sdk \
JAVA_HOME=/home/reason/Temporary/ChatGPTProjects/ReasonNotes/toolchain/Android/install/jdk-17.0.20.1+1 \
./gradlew testDebugUnitTest \
  --tests org.reasonnotes.probe.DeliveryLedgerTest \
  --tests org.reasonnotes.probe.ExchangeDirectoryTest \
  --no-daemon --console=plain
```

If green, run the full 99-test suite, rebuild/install the APK, and repeat the
physical round trip. Measure separately: scan start to durable Android import,
one outbound receipt carrier arriving on Linux, and Linux durable confirmation.
Do not claim success from share launch or file arrival alone. Do not proceed to
production cutover.
