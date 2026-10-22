# Modern Android JSON v1 Integration Plan

**Goal:** Integrate the approved canonical JSON v1 transport into Android commit
`9e2cd049282a31b1f9db45bd3ec09b6da9bc5721` without changing the modern UI or
the established revision, receipt, history, conflict, deletion, and retry
semantics.

**Constraints:** Keep legacy individual `.md` imports. Do not add Android ZIP
compatibility, deploy an APK, use ADB, modify phone data, or touch the protected
recovery checkout.

## Task 1: Canonical transport codec

- Add failing fixture and rejection tests matching the desktop v1 bytes.
- Implement strict UTF-8, schema, ordering, hash, limit, duplicate, and envelope
  identity validation.
- Persist one content-addressed JSON carrier atomically.

## Task 2: Modern receive integration

- Add failing mixed, malformed-later-member, and repeated-carrier tests.
- Discover canonical JSON carriers while retaining optimized legacy `.md`
  scanning.
- Validate every carrier and embedded envelope before any repository or ledger
  mutation, then use the existing per-envelope import paths.

## Task 3: Modern send integration

- Make every non-empty plan, including one item, create and share exactly one
  JSON carrier URI.
- Keep per-envelope ledgers pending unless that one share request is accepted.
- Remove the unused ZIP generator and its tests; do not add ZIP receive support.

## Task 4: Offline verification and durable checkpoint

- Run focused tests, the complete Android unit suite, and Android candidate
  builds offline.
- Review the exact diff for unrelated UI or behavior changes.
- Commit and push the identical Forge branch SHA to `handoff` and `github`, then
  verify both remote full SHAs against local `HEAD`.
