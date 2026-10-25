package org.reasonnotes.probe;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Synchronous, testable scan-before-send transaction. Call it from a worker thread. */
public final class SyncCoordinator {
    public enum FileKind { REVISION, RECEIPT, STATE, ACK }
    public record OutgoingFile(FileKind kind, String identity, File file) {
        public OutgoingFile {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(file, "file");
        }
    }
    public record FlushEvidence(boolean saved, String detail) {
        public static FlushEvidence success() { return new FlushEvidence(true, "draft saved"); }
    }
    public record ScanEvidence(
            int imported, int confirmed, int receiptsQueued, List<String> errors) {
        public ScanEvidence { errors = List.copyOf(errors); }
    }
    public record RequestEvidence(boolean requested, String detail) {}

    @FunctionalInterface public interface DraftFlusher { FlushEvidence flush(); }
    @FunctionalInterface public interface IncomingScanner { ScanEvidence scan(); }
    @FunctionalInterface public interface TransferRequester {
        RequestEvidence request(List<OutgoingFile> files);
    }

    private final ProbeRepository repository;
    private final CurrentNoteStore currentStore;
    private final DeliveryLedger ledger;
    private final ReceiptStore receipts;
    private final SyncPlanner planner;
    private final String targetDevice;
    private final DraftFlusher flusher;
    private final IncomingScanner scanner;
    private final TransferRequester requester;
    private final Supplier<Instant> clock;

    public SyncCoordinator(
            ProbeRepository repository,
            DeliveryLedger ledger,
            ReceiptStore receipts,
            SyncPlanner planner,
            String targetDevice,
            DraftFlusher flusher,
            IncomingScanner scanner,
            TransferRequester requester,
            Supplier<Instant> clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.currentStore = null;
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.targetDevice = Objects.requireNonNull(targetDevice, "targetDevice");
        this.flusher = Objects.requireNonNull(flusher, "flusher");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.requester = Objects.requireNonNull(requester, "requester");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SyncCoordinator(
            CurrentNoteStore currentStore,
            String targetDevice,
            DraftFlusher flusher,
            IncomingScanner scanner,
            TransferRequester requester,
            Supplier<Instant> clock) {
        this.repository = null;
        this.currentStore = Objects.requireNonNull(currentStore, "currentStore");
        this.ledger = null;
        this.receipts = null;
        this.planner = null;
        this.targetDevice = Objects.requireNonNull(targetDevice, "targetDevice");
        this.flusher = Objects.requireNonNull(flusher, "flusher");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.requester = Objects.requireNonNull(requester, "requester");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SyncStatus synchronize(OutgoingSelection selection, boolean retryUnconfirmed) {
        Objects.requireNonNull(selection, "selection");
        if (currentStore != null) return synchronizeCurrent(selection);
        try {
            FlushEvidence flush = flusher.flush();
            if (!flush.saved()) return SyncStatus.error(flush.detail());

            ScanEvidence scan = scanner.scan();
            if (!scan.errors().isEmpty()) {
                String detail = String.join("; ", scan.errors());
                return new SyncStatus(SyncStatus.Phase.ERROR, 0, 0,
                        scan.imported(), scan.confirmed(), scan.receiptsQueued(), scan.errors(), detail);
            }

            SyncPlanner.Plan plan = planner.plan(selection, retryUnconfirmed);
            // Reusing one history snapshot avoids re-reading every revision
            // and rebuilding a graph for each outgoing file (86+ on real sync).
            Map<String, RevisionEnvelope> revisions = new HashMap<>();
            for (RevisionEnvelope revision : repository.revisions()) {
                revisions.put(revision.revisionId(), revision);
            }
            ArrayList<OutgoingFile> files = new ArrayList<>();
            ArrayList<String> errors = new ArrayList<>();
            for (String id : plan.revisionIds()) {
                RevisionEnvelope revision = revisions.get(id);
                if (revision == null) {
                    errors.add("missing planned revision " + id);
                    continue;
                }
                File file = new File(new File(repository.root(), "history/" + revision.noteId()), id + ".md");
                if (!file.isFile()) errors.add("missing revision file " + id);
                else files.add(new OutgoingFile(FileKind.REVISION, id, file));
            }
            for (String id : plan.receiptIds()) {
                try {
                    files.add(new OutgoingFile(FileKind.RECEIPT, id, receipts.outboxFile(id)));
                } catch (IllegalArgumentException error) {
                    errors.add("missing receipt file " + id);
                }
            }
            errors.addAll(plan.missingRevisionIds().stream()
                    .map(id -> "missing ledger revision " + id).toList());
            if (!errors.isEmpty()) {
                String detail = String.join("; ", errors);
                return new SyncStatus(SyncStatus.Phase.ERROR, 0, 0,
                        scan.imported(), scan.confirmed(), scan.receiptsQueued(), errors, detail);
            }
            if (files.isEmpty()) {
                return new SyncStatus(SyncStatus.Phase.COMPLETE, 0, 0,
                        scan.imported(), scan.confirmed(), scan.receiptsQueued(), List.of(),
                        "nothing eligible to transfer");
            }

            RequestEvidence request = requester.request(List.copyOf(files));
            if (!request.requested()) {
                return new SyncStatus(SyncStatus.Phase.ERROR, 0, 0,
                        scan.imported(), scan.confirmed(), scan.receiptsQueued(),
                        List.of(request.detail()), request.detail());
            }
            Instant observed = clock.get();
            List<String> revisionIds = files.stream()
                    .filter(file -> file.kind() == FileKind.REVISION)
                    .map(OutgoingFile::identity).toList();
            List<String> receiptIds = files.stream()
                    .filter(file -> file.kind() == FileKind.RECEIPT)
                    .map(OutgoingFile::identity).toList();
            ledger.recordRequestedBatch(revisionIds, targetDevice, observed);
            receipts.recordRequestedBatch(receiptIds, targetDevice, observed);
            int revisionCount = revisionIds.size();
            int receiptCount = receiptIds.size();
            return new SyncStatus(SyncStatus.Phase.TRANSFER_REQUESTED,
                    revisionCount, receiptCount, scan.imported(), scan.confirmed(),
                    scan.receiptsQueued(), List.of(), request.detail());
        } catch (RuntimeException error) {
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return SyncStatus.error(detail);
        }
    }

    private SyncStatus synchronizeCurrent(OutgoingSelection selection) {
        try {
            FlushEvidence flush = flusher.flush();
            if (!flush.saved()) return SyncStatus.error(flush.detail());
            ScanEvidence scan = scanner.scan();
            if (!scan.errors().isEmpty()) {
                String detail = String.join("; ", scan.errors());
                return new SyncStatus(SyncStatus.Phase.ERROR, 0, 0,
                        scan.imported(), scan.confirmed(), scan.receiptsQueued(),
                        scan.errors(), detail);
            }

            List<StateEnvelope> states = currentStore.pendingStates().stream()
                    .filter(state -> selection.includes(state.noteId()))
                    .filter(state -> currentStore.conflict(state.noteId()) == null)
                    .toList();
            List<java.util.UUID> conflicted = currentStore.conflicts().stream()
                    .map(CurrentNoteStore.Conflict::noteId)
                    .filter(selection::includes).sorted().toList();
            List<StateAckEnvelope> acknowledgments = currentStore.pendingAcks();
            ArrayList<OutgoingFile> files = new ArrayList<>();
            for (StateEnvelope state : states) {
                File file = currentStore.pendingStateFile(state.noteId());
                if (!file.isFile()) return SyncStatus.error("pending note changed during sync");
                files.add(new OutgoingFile(FileKind.STATE, state.changeId(), file));
            }
            for (StateAckEnvelope ack : acknowledgments) {
                File file = currentStore.outgoingAckFile(ack);
                if (!file.isFile()) return SyncStatus.error("pending acknowledgment changed during sync");
                files.add(new OutgoingFile(FileKind.ACK, ack.ackId(), file));
            }
            if (files.isEmpty()) {
                if (!conflicted.isEmpty()) {
                    String detail = "conflict requires choice: " + conflicted.stream()
                            .map(java.util.UUID::toString)
                            .collect(java.util.stream.Collectors.joining(","));
                    return SyncStatus.error(detail);
                }
                return new SyncStatus(SyncStatus.Phase.COMPLETE, 0, 0,
                        scan.imported(), scan.confirmed(), scan.receiptsQueued(), List.of(),
                        "selected notes are synchronized");
            }
            RequestEvidence request = requester.request(List.copyOf(files));
            if (!request.requested()) {
                return new SyncStatus(SyncStatus.Phase.ERROR, 0, 0,
                        scan.imported(), scan.confirmed(), scan.receiptsQueued(),
                        List.of(request.detail()), request.detail());
            }
            currentStore.markAcksRequested(acknowledgments);
            if (states.isEmpty()) {
                if (!conflicted.isEmpty()) {
                    String detail = "conflict requires choice: " + conflicted.stream()
                            .map(java.util.UUID::toString)
                            .collect(java.util.stream.Collectors.joining(","));
                    return new SyncStatus(SyncStatus.Phase.ERROR, 0, acknowledgments.size(),
                            scan.imported(), scan.confirmed(), scan.receiptsQueued(),
                            List.of(detail), detail);
                }
                return new SyncStatus(SyncStatus.Phase.COMPLETE, 0, acknowledgments.size(),
                        scan.imported(), scan.confirmed(), scan.receiptsQueued(), List.of(),
                        "selected notes are synchronized");
            }
            String requestedIds = states.stream().map(StateEnvelope::changeId)
                    .sorted().collect(java.util.stream.Collectors.joining(","));
            String conflictDetail = conflicted.isEmpty() ? "" : "; conflict requires choice: "
                    + conflicted.stream().map(java.util.UUID::toString)
                            .collect(java.util.stream.Collectors.joining(","));
            return new SyncStatus(SyncStatus.Phase.TRANSFER_REQUESTED,
                    states.size(), acknowledgments.size(), scan.imported(), scan.confirmed(),
                    scan.receiptsQueued(), List.of(),
                    "waiting for peer confirmation: " + requestedIds + conflictDetail);
        } catch (RuntimeException error) {
            return SyncStatus.error(error.getMessage());
        }
    }
}
