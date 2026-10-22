# ReasonNotes Canonical JSON Transport Bundle

This repository implements the Android half of the cross-platform format
specified identically in the ReasonNotesKvantum Forge candidate.

The v1 payload is canonical UTF-8 JSON without BOM or trailing newline. It has
top-level keys `format`, `items`, `version`; each item has `envelope`, `id`,
`kind`. Compact serialization, literal Unicode, and items sorted by `kind`
then `id` are mandatory. `format` is `reasonnotes-transport-bundle`, `version`
is `1`, and the filename is
`reasonnotes-bundle-v1-<sha256-of-exact-payload-bytes>.json`.

Bundles contain 1 to 256 unchanged canonical revision/receipt envelopes, each
at most 16 MiB and at most 64 MiB total/payload. Receivers verify filename,
hash, UTF-8, exact schema/order, canonical reserialization, limits, unique
identities, and each identity through the existing envelope parser before any
repository mutation.

One accepted KDE Connect request advances every included envelope through the
existing per-envelope ledger. Durable confirmation still requires the existing
receipt path. Individual `.md` input remains compatible. Android neither reads
nor creates ZIP carriers because no Android ZIP carrier was deployed.

Acceptance requires matching Python/Java fixture bytes, unit/build evidence,
and a physical bidirectional candidate round trip with payload count and
elapsed time measured. Production remains untouched.
