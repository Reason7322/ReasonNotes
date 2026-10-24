# Android Sync Correctness and Performance Design

## Intent

Keep the existing JSON v1 protocol and UI while making sync ledger updates
race-safe and preventing old exchange artifacts from freezing ordinary note
work. A sync receipt may confirm only a durably recorded transfer request.

## Chosen approach

Serialize/coalesce incoming scans process-wide, protect complete delivery-ledger
read/modify/write transactions across instances, and persist a private index of
successfully processed exchange documents. New or metadata-changed documents
are still read and cryptographically validated; unchanged, previously validated
documents are skipped. Failed/rejected documents are never cached. No shared
exchange files are deleted and no protocol or global KDE Connect setting changes.

## Alternatives rejected

- Serialization alone preserves 40-second rescans.
- Deleting/archiving shared exchange files is externally destructive.
- Trusting filenames without first validating bytes weakens receipt integrity.

## Acceptance

Concurrent ledger writers cannot lose entries; overlapping scan requests execute
one scan at a time; a second unchanged scan reads no carrier payloads; changed/new
files are processed; corrupt files remain retryable; note UI work does not wait
on exchange scanning. Physical installation remains separately authorized.
