# Milestone 1 Automated Evidence

## Evidence classification

Everything in this document is **automated filesystem simulation**. It is not Linux-live transfer proof, Android runtime proof, or physical POCO convergence proof.

## Commands

```sh
PYTHONPATH=src python3 -m unittest discover -s tests -v
scripts/run-two-device-demo.sh "$(mktemp -d)"
```

## Behaviors covered

- Canonical Unicode Markdown envelopes and strict integrity validation.
- Complete-ancestry head derivation, duplicates, renamed deliveries, and out-of-order arrivals.
- Immutable history followed by atomic generated-state reconciliation.
- Explicit Keep Linux, Keep Android, and Skip behavior.
- Multi-parent resolution convergence with superseded revisions retained.
- Concurrent edit/deletion selection in both directions.
- Stale resolution remaining conflicted with an unacknowledged head.
- Bounded delivery request state and separate request/completion/import evidence kinds.
- Scan-before-send Linux orchestration without live KDE Connect calls.
- Two isolated repository directories reaching equal revision IDs and body hashes.

## Final automated verification

- Python: 61/61 tests passed.
- Android host: 17/17 tests passed, including the Java/Python compatibility
  vectors, routing regression, system-chrome layout, and multi-note repository.
- `assembleDebug` and `assembleDebugAndroidTest` succeeded.

Physical runtime claims remain outside the scope of this document. They are
recorded separately in `PHONE_TEST_RESULTS.md`. The only physical matrix item
not run was a deliberately interrupted large in-flight transfer.

