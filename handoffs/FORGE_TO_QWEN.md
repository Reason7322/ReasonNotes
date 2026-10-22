# Forge -> Qwen Handoff

## Checkpoint

- Branch: `forge/android-json-v1-integration`
- Exact base: `9e2cd049282a31b1f9db45bd3ec09b6da9bc5721`
- Reference-only stale implementation:
  `71f0e3124b1f45c06fc8cf3fc9c8c4779e010066`
- Desktop protocol checkpoint:
  `88def77e2bfe3c6b2b92897b5d134395a761ffd3`
- Verify the final pushed full SHA on both `handoff` and `github`; this file
  cannot contain the hash of its own commit.

## Delta

- Added a strict canonical JSON v1 codec matching the desktop fixture exactly,
  including literal Unicode, deterministic ordering, content-addressed names,
  collision suffix acceptance, limits, and full embedded-envelope identity
  validation.
- Added JSON carrier discovery and import to the modern optimized Android scan.
  The full carrier validates before mutation; legacy `.md` imports remain.
- Repeated carriers are idempotent. A valid receipt followed by a malformed
  revision cannot produce confirmation or repository mutation.
- Every non-empty outgoing plan now creates one JSON carrier and grants one
  FileProvider URI. Existing per-envelope ledgers advance only after the share
  request is accepted.
- Removed the undeployed Android ZIP generator and its tests; no ZIP receive
  compatibility was added.
- Preserved modern Android UI, editor, startup, list, history, conflict,
  deletion, retry, and durable receipt semantics.

## Offline verification

- Clean `testDebugUnitTest`: 157/157 passed, 29 suites, zero failures/errors.
- Clean `assembleDebug`: passed.
- Clean `assembleDebugAndroidTest`: passed. The inherited instrumented exchange
  fixture was updated only to call the already-current ledger/receipt scan API.
- App APK: `android-probe/app/build/outputs/apk/debug/app-debug.apk`
  - size: 2,498,156 bytes
  - SHA-256:
    `e7cbf93bb9f3e1821d361a4b32cd50681b2c2350b12d5eac7339911e1bb9ee97`
- Test APK SHA-256:
  `eb5845c2ec9ef4eb5913a5a74695fad4068695dfbc1fba29a3289045c3b67765`

## Evidence boundary / next action

No ADB command, installation, phone-data change, or live synchronization was
performed. Physical Fedora/Android round-trip timing and durable receipt
confirmation remain the separately authorized next stage. Do not deploy this
candidate without that authorization, and do not use the protected recovery
checkout for follow-up work.
