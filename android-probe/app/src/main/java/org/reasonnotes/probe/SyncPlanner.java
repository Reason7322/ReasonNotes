package org.reasonnotes.probe;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Pure outgoing planning: selection never changes incoming scans or receipt eligibility. */
public final class SyncPlanner {
    public record Plan(
            List<String> revisionIds,
            List<String> receiptIds,
            List<String> skippedRevisionIds,
            List<String> missingRevisionIds,
            List<String> confirmedRevisionIds,
            List<String> blockedByAncestry) {}

    public record CatalogueItem(
            UUID noteId,
            String title,
            boolean deleted,
            boolean conflicted,
            Instant modifiedUtc,
            String deliveryState) {}

    private final ProbeRepository repository;
    private final DeliveryLedger ledger;
    private final ReceiptStore receipts;
    private final String localDevice;
    private final String targetDevice;

    public SyncPlanner(
            ProbeRepository repository,
            DeliveryLedger ledger,
            ReceiptStore receipts,
            String localDevice,
            String targetDevice) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.localDevice = Objects.requireNonNull(localDevice, "localDevice");
        this.targetDevice = Objects.requireNonNull(targetDevice, "targetDevice");
    }

    public Plan plan(OutgoingSelection selection, boolean retryUnconfirmed) {
        Objects.requireNonNull(selection, "selection");
        Map<String, RevisionEnvelope> revisions = registerLocalHistory();
        Set<String> eligible = new HashSet<>(
                ledger.eligible(targetDevice, retryUnconfirmed, List.of()));
        TreeSet<String> send = new TreeSet<>();
        TreeSet<String> skipped = new TreeSet<>();
        TreeSet<String> missing = new TreeSet<>();
        TreeSet<String> confirmed = new TreeSet<>();
        TreeSet<String> blocked = new TreeSet<>();

        for (String id : eligible) {
            RevisionEnvelope revision = revisions.get(id);
            if (revision == null) {
                missing.add(id);
            } else if (!selection.includes(revision.noteId())) {
                skipped.add(id);
            }
        }

        for (RevisionEnvelope candidate : revisions.values()) {
            if (!candidate.originDevice().equals(localDevice)
                    || !selection.includes(candidate.noteId())
                    || !eligible.contains(candidate.revisionId())) {
                continue;
            }
            DependencyResult dependencies = dependencies(candidate, revisions, eligible);
            confirmed.addAll(dependencies.confirmed());
            if (dependencies.blocked()) blocked.add(candidate.revisionId());
            else send.addAll(dependencies.send());
        }

        for (RevisionEnvelope revision : revisions.values()) {
            if (!selection.includes(revision.noteId()) || !revision.originDevice().equals(localDevice)) continue;
            DeliveryLedger.Entry entry = ledger.entry(revision.revisionId(), targetDevice);
            if (entry != null && entry.state().equals("confirmed")) confirmed.add(revision.revisionId());
        }

        return new Plan(
                List.copyOf(send),
                receipts.eligible(targetDevice, retryUnconfirmed, List.of()),
                List.copyOf(skipped),
                List.copyOf(missing),
                List.copyOf(confirmed),
                List.copyOf(blocked));
    }

    public Plan planForced(List<String> revisionIds, List<String> receiptIds) {
        Map<String, RevisionEnvelope> revisions = registerLocalHistory();
        TreeSet<String> send = new TreeSet<>();
        TreeSet<String> missing = new TreeSet<>();
        for (String id : ledger.eligible(targetDevice, false, revisionIds)) {
            if (revisions.containsKey(id)) send.add(id);
            else missing.add(id);
        }
        return new Plan(
                List.copyOf(send),
                receipts.eligible(targetDevice, false, receiptIds),
                List.of(),
                List.copyOf(missing),
                List.of(),
                List.of());
    }

    public List<CatalogueItem> catalogue() {
        Map<String, RevisionEnvelope> all = registerLocalHistory();
        ArrayList<CatalogueItem> result = new ArrayList<>();
        for (UUID noteId : repository.noteIds()) {
            List<RevisionEnvelope> noteHistory = all.values().stream()
                    .filter(revision -> revision.noteId().equals(noteId))
                    .toList();
            List<RevisionEnvelope> heads = repository.heads(noteId);
            if (heads.isEmpty()) continue;
            boolean deleted = heads.size() == 1 && heads.get(0).deleted();
            String state = deliveryState(noteHistory);
            if (deleted && state.equals("confirmed")) continue;
            RevisionEnvelope titleSource = noteHistory.stream()
                    .filter(revision -> !revision.deleted())
                    .max(Comparator.comparing(RevisionEnvelope::modifiedUtc)
                            .thenComparing(RevisionEnvelope::revisionId))
                    .orElse(null);
            Instant modified = heads.stream().map(RevisionEnvelope::modifiedUtc)
                    .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
            result.add(new CatalogueItem(
                    noteId,
                    titleSource == null ? "Deleted note" : title(titleSource.body()),
                    deleted,
                    heads.size() > 1,
                    modified,
                    state));
        }
        result.sort(Comparator.comparing(CatalogueItem::modifiedUtc)
                .thenComparing(item -> item.noteId().toString())
                .reversed());
        return List.copyOf(result);
    }

    private Map<String, RevisionEnvelope> registerLocalHistory() {
        HashMap<String, RevisionEnvelope> result = new HashMap<>();
        for (RevisionEnvelope revision : repository.revisions()) {
            result.put(revision.revisionId(), revision);
            if (revision.originDevice().equals(localDevice)) {
                ledger.registerLocal(revision.revisionId(), targetDevice);
            }
        }
        return result;
    }

    private DependencyResult dependencies(
            RevisionEnvelope candidate,
            Map<String, RevisionEnvelope> revisions,
            Set<String> eligible) {
        ArrayDeque<String> remaining = new ArrayDeque<>();
        remaining.add(candidate.revisionId());
        HashSet<String> visited = new HashSet<>();
        TreeSet<String> send = new TreeSet<>();
        TreeSet<String> confirmed = new TreeSet<>();
        boolean blocked = false;
        while (!remaining.isEmpty()) {
            String id = remaining.removeLast();
            if (!visited.add(id)) continue;
            RevisionEnvelope revision = revisions.get(id);
            if (revision == null || !revision.noteId().equals(candidate.noteId())) {
                blocked = true;
                continue;
            }
            remaining.addAll(revision.parents());
            if (revision.originDevice().equals(targetDevice)) continue;
            if (!revision.originDevice().equals(localDevice)) {
                blocked = true;
                continue;
            }
            DeliveryLedger.Entry entry = ledger.entry(id, targetDevice);
            if (entry != null && entry.state().equals("confirmed")) {
                confirmed.add(id);
            } else if (eligible.contains(id)) {
                send.add(id);
            } else {
                blocked = true;
            }
        }
        return new DependencyResult(send, confirmed, blocked);
    }

    private String deliveryState(List<RevisionEnvelope> history) {
        boolean pending = false;
        boolean requested = false;
        boolean attention = false;
        boolean confirmed = false;
        for (RevisionEnvelope revision : history) {
            if (!revision.originDevice().equals(localDevice)) continue;
            DeliveryLedger.Entry entry = ledger.entry(revision.revisionId(), targetDevice);
            if (entry == null) continue;
            switch (entry.state()) {
                case "pending" -> pending = true;
                case "transfer-requested" -> requested = true;
                case "needs-attention" -> attention = true;
                case "confirmed" -> confirmed = true;
                default -> throw new IllegalStateException("unknown delivery state");
            }
        }
        if (attention) return "needs-attention";
        if (pending) return "pending";
        if (requested) return "unconfirmed";
        if (confirmed) return "confirmed";
        return "incoming-only";
    }

    private static String title(String body) {
        String value = NoteDocument.split(body).title();
        return value.isEmpty() ? "Untitled" : value;
    }

    private record DependencyResult(Set<String> send, Set<String> confirmed, boolean blocked) {}
}
