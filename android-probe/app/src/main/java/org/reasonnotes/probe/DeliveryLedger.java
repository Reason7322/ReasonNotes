package org.reasonnotes.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DeliveryLedger {
    public record Entry(
            String revisionId,
            String targetDevice,
            String state,
            int attempts,
            Instant lastRequested,
            String receiptId,
            Instant confirmedUtc) {}
    public record Event(String kind, String revisionId, Instant observed, String detail) {}

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private final File directory;
    private final File entriesFile;
    private final File eventsFile;
    private final int maxRequests;

    public DeliveryLedger(File directory, int maxRequests) {
        if (maxRequests < 1) throw new IllegalArgumentException("maxRequests");
        this.directory = directory;
        this.entriesFile = new File(directory, "delivery.tsv");
        this.eventsFile = new File(directory, "events.tsv");
        this.maxRequests = maxRequests;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("state directory");
    }

    private static String key(String revision, String target) { return target + "\u0000" + revision; }
    private static void validateRevision(String revision) {
        if (!HASH.matcher(revision).matches()) throw new IllegalArgumentException("revision ID");
    }

    private Map<String, Entry> readEntries() {
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        if (!entriesFile.isFile()) return result;
        try {
            for (String line : Files.readAllLines(entriesFile.toPath(), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] values = line.split("\\t", -1);
                if (values.length != 5 && values.length != 7) {
                    throw new IllegalArgumentException("delivery entry field count");
                }
                validateRevision(values[0]);
                if (!List.of("pending", "transfer-requested", "needs-attention", "confirmed")
                        .contains(values[2])) {
                    throw new IllegalArgumentException("delivery state");
                }
                Entry entry = new Entry(
                        values[0], values[1], values[2], Integer.parseInt(values[3]),
                        values[4].isEmpty() ? null : Instant.parse(values[4]),
                        values.length == 5 || values[5].isEmpty() ? null : values[5],
                        values.length == 5 || values[6].isEmpty() ? null : Instant.parse(values[6]));
                if (entry.attempts() < 0) throw new IllegalArgumentException("attempt count");
                if (entry.state().equals("confirmed")) {
                    if (entry.attempts() < 1 || entry.receiptId() == null || entry.confirmedUtc() == null) {
                        throw new IllegalArgumentException("invalid confirmed delivery");
                    }
                    validateRevision(entry.receiptId());
                } else if (entry.receiptId() != null || entry.confirmedUtc() != null) {
                    throw new IllegalArgumentException("unconfirmed delivery has receipt metadata");
                }
                result.put(key(entry.revisionId(), entry.targetDevice()), entry);
            }
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("invalid delivery ledger", error);
        }
    }

    private void writeEntries(Map<String, Entry> entries) {
        StringBuilder value = new StringBuilder();
        entries.values().stream()
                .sorted(Comparator.comparing(Entry::targetDevice).thenComparing(Entry::revisionId))
                .forEach(entry -> value.append(entry.revisionId()).append('\t')
                        .append(entry.targetDevice()).append('\t').append(entry.state()).append('\t')
                        .append(entry.attempts()).append('\t')
                        .append(entry.lastRequested() == null ? "" : entry.lastRequested()).append('\t')
                        .append(entry.receiptId() == null ? "" : entry.receiptId()).append('\t')
                        .append(entry.confirmedUtc() == null ? "" : entry.confirmedUtc()).append('\n'));
        atomicWrite(entriesFile, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicWrite(File target, byte[] data) {
        try {
            File temporary = File.createTempFile("." + target.getName(), ".tmp", target.getParentFile());
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(data);
                    output.flush();
                    output.getFD().sync();
                }
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot persist delivery ledger", error);
        }
    }

    /**
     * Upgrade historical physical KDE Connect keys to the logical revision peer.
     * Reconcile ordinary pending/requested/attention differences conservatively,
     * without resetting retries or inventing a receipt. Contradictory confirmed
     * receipt evidence remains fatal and leaves the entire ledger unchanged.
     */
    public boolean migrateTarget(String previousTarget, String logicalPeer) {
        return migrateTargets(List.of(previousTarget), logicalPeer);
    }

    public boolean migrateTargets(List<String> previousTargets, String logicalPeer) {
        if (logicalPeer == null || logicalPeer.isBlank() || previousTargets == null
                || previousTargets.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("target identity must not be empty");
        }
        // Stage *all* transformations in memory before the single atomic write,
        // so conflicting historic physical IDs cannot cause partial migration.
        Map<String, Entry> entries = readEntries();
        LinkedHashMap<String, Entry> converted = new LinkedHashMap<>(entries);
        boolean changed = false;
        for (String previousTarget : previousTargets.stream().distinct().toList()) {
            if (previousTarget.equals(logicalPeer)) continue;
            for (Entry legacy : entries.values()) {
                if (!legacy.targetDevice().equals(previousTarget)) continue;
                String destination = key(legacy.revisionId(), logicalPeer);
                Entry existing = converted.get(destination);
                Entry normalized = new Entry(
                        legacy.revisionId(), logicalPeer, legacy.state(),
                        legacy.attempts(), legacy.lastRequested(),
                        legacy.receiptId(), legacy.confirmedUtc());
                Entry merged = existing == null ? normalized : mergeEvidence(existing, normalized);
                converted.remove(key(legacy.revisionId(), previousTarget));
                converted.put(destination, merged);
                changed = true;
            }
        }
        if (changed) writeEntries(converted);
        return changed;
    }

    private Entry mergeEvidence(Entry a, Entry b) {
        if (!a.revisionId().equals(b.revisionId())
                || !a.targetDevice().equals(b.targetDevice())) {
            throw new IllegalArgumentException("cannot merge different delivery identities");
        }
        Entry confirmedA = a.state().equals("confirmed") ? a : null;
        Entry confirmedB = b.state().equals("confirmed") ? b : null;
        if (confirmedA != null && confirmedB != null
                && (!Objects.equals(confirmedA.receiptId(), confirmedB.receiptId())
                || !Objects.equals(confirmedA.confirmedUtc(), confirmedB.confirmedUtc()))) {
            throw new IllegalStateException(
                    "contradictory confirmation receipts; no migration performed");
        }

        // Transfer requests across two historical keys might describe the same
        // attempt; adding counters would invent retries. Preserve the maximum.
        int attempts = Math.max(a.attempts(), b.attempts());
        Instant latest = a.lastRequested() == null ? b.lastRequested()
                : b.lastRequested() == null ? a.lastRequested()
                : a.lastRequested().isAfter(b.lastRequested())
                        ? a.lastRequested() : b.lastRequested();
        Entry confirmed = confirmedA != null ? confirmedA : confirmedB;
        if (confirmed != null) {
            return new Entry(a.revisionId(), a.targetDevice(), "confirmed",
                    attempts, latest, confirmed.receiptId(), confirmed.confirmedUtc());
        }

        // Never downgrade needs-attention or unblock retries just because the
        // two old keys reported different unconfirmed states.
        String state = a.state().equals("needs-attention")
                || b.state().equals("needs-attention") || attempts >= maxRequests
                        ? "needs-attention"
                        : attempts > 0 ? "transfer-requested" : "pending";
        return new Entry(a.revisionId(), a.targetDevice(), state,
                attempts, latest, null, null);
    }

    public void registerLocal(String revisionId, String targetDevice) {
        registerLocals(List.of(revisionId), targetDevice);
    }

    /** Register all newly discovered local revisions atomically. Never
     * overwrite existing pending/requested/confirmed/attention evidence.
     * A catalogue refresh must not re-read and fsync the ledger per revision. */
    public void registerLocals(List<String> revisionIds, String targetDevice) {
        Objects.requireNonNull(revisionIds, "revisionIds");
        if (targetDevice == null || targetDevice.isBlank()) {
            throw new IllegalArgumentException("target identity must not be empty");
        }
        for (String id : revisionIds) validateRevision(id);
        if (revisionIds.isEmpty()) return;
        Map<String, Entry> entries = readEntries();
        boolean changed = false;
        for (String id : revisionIds) {
            String entryKey = key(id, targetDevice);
            if (entries.containsKey(entryKey)) continue;
            entries.put(entryKey,
                    new Entry(id, targetDevice, "pending", 0, null, null, null));
            changed = true;
        }
        if (changed) writeEntries(entries);
    }

    public Entry entry(String revisionId, String targetDevice) {
        return readEntries().get(key(revisionId, targetDevice));
    }

    public List<Entry> entries(String targetDevice) {
        return readEntries().values().stream()
                .filter(entry -> entry.targetDevice().equals(targetDevice))
                .sorted(Comparator.comparing(Entry::revisionId)).toList();
    }

    public List<String> eligible(String targetDevice, boolean retryUnconfirmed, List<String> forceIds) {
        Map<String, Entry> entries = readEntries();
        if (!forceIds.isEmpty()) {
            return forceIds.stream().distinct().sorted()
                    .filter(id -> entries.containsKey(key(id, targetDevice)))
                    .filter(id -> !entries.get(key(id, targetDevice)).state().equals("confirmed"))
                    .toList();
        }
        return entries.values().stream()
                .filter(entry -> entry.targetDevice().equals(targetDevice))
                .filter(entry -> entry.state().equals("pending")
                        || (retryUnconfirmed && entry.state().equals("transfer-requested")
                        && entry.attempts() < maxRequests))
                .map(Entry::revisionId).sorted().toList();
    }

    public void recordRequested(String revisionId, String targetDevice, Instant observed) {
        Map<String, Entry> entries = readEntries();
        String key = key(revisionId, targetDevice);
        Entry prior = entries.get(key);
        if (prior == null) throw new IllegalArgumentException("unregistered revision");
        if (prior.state().equals("confirmed")) {
            throw new IllegalArgumentException("confirmed revision cannot be requested again");
        }
        int attempts = prior.attempts() + 1;
        entries.put(key, new Entry(revisionId, targetDevice,
                attempts >= maxRequests ? "needs-attention" : "transfer-requested",
                attempts, observed, null, null));
        writeEntries(entries);
        recordEvent("transfer-requested", revisionId, observed, "target=" + targetDevice);
    }

    public boolean recordConfirmed(
            String revisionId,
            String targetDevice,
            String receiptId,
            Instant observed) {
        validateRevision(revisionId);
        validateRevision(receiptId);
        if (observed == null || observed.getNano() != 0) {
            throw new IllegalArgumentException("whole-second confirmation time required");
        }
        Map<String, Entry> entries = readEntries();
        String key = key(revisionId, targetDevice);
        Entry prior = entries.get(key);
        if (prior == null) throw new IllegalArgumentException("unregistered revision");
        if (prior.attempts() < 1) {
            throw new IllegalArgumentException("revision was never requested to this peer");
        }
        if (prior.state().equals("confirmed")) {
            if (!receiptId.equals(prior.receiptId())) {
                throw new IllegalArgumentException("confirmed revision has a different receipt");
            }
            String detail = "receipt=" + receiptId + " target=" + targetDevice;
            boolean recorded = events().stream().anyMatch(event ->
                    event.kind().equals("reasonnotes-confirmed")
                            && event.revisionId().equals(revisionId)
                            && event.detail().equals(detail));
            if (!recorded) {
                recordEvent(
                        "reasonnotes-confirmed",
                        revisionId,
                        prior.confirmedUtc(),
                        detail);
            }
            return false;
        }
        entries.put(key, new Entry(
                revisionId,
                targetDevice,
                "confirmed",
                prior.attempts(),
                prior.lastRequested(),
                receiptId,
                observed));
        writeEntries(entries);
        recordEvent(
                "reasonnotes-confirmed",
                revisionId,
                observed,
                "receipt=" + receiptId + " target=" + targetDevice);
        return true;
    }

    public boolean recordImported(
            String revisionId, Instant observed, String detail) {
        validateRevision(revisionId);
        if (observed == null || observed.getNano() != 0) {
            throw new IllegalArgumentException("whole-second import time required");
        }
        boolean recorded = events().stream().anyMatch(event ->
                event.kind().equals("reasonnotes-imported")
                        && event.revisionId().equals(revisionId));
        if (recorded) return false;
        recordEvent("reasonnotes-imported", revisionId, observed, detail);
        return true;
    }

    public void recordEvent(String kind, String revisionId, Instant observed, String detail) {
        validateRevision(revisionId);
        if (!List.of(
                "transfer-requested",
                "kdeconnect-transfer-complete",
                "reasonnotes-imported",
                "reasonnotes-confirmed").contains(kind)) {
            throw new IllegalArgumentException("event kind");
        }
        String encoded = Base64.getEncoder().encodeToString(detail.getBytes(StandardCharsets.UTF_8));
        String line = kind + "\t" + revisionId + "\t" + observed + "\t" + encoded + "\n";
        try (FileOutputStream output = new FileOutputStream(eventsFile, true)) {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (IOException error) {
            throw new IllegalStateException("cannot append event", error);
        }
    }

    public List<Event> events() {
        ArrayList<Event> result = new ArrayList<>();
        if (!eventsFile.isFile()) return result;
        try {
            for (String line : Files.readAllLines(eventsFile.toPath(), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] values = line.split("\\t", -1);
                result.add(new Event(values[0], values[1], Instant.parse(values[2]),
                        new String(Base64.getDecoder().decode(values[3]), StandardCharsets.UTF_8)));
            }
            return List.copyOf(result);
        } catch (Exception error) {
            throw new IllegalStateException("invalid event log", error);
        }
    }
}
