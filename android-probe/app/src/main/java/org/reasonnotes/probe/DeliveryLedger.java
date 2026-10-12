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
import java.util.regex.Pattern;

public final class DeliveryLedger {
    public record Entry(String revisionId, String targetDevice, String state, int attempts, Instant lastRequested) {}
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
                Entry entry = new Entry(
                        values[0], values[1], values[2], Integer.parseInt(values[3]),
                        values[4].isEmpty() ? null : Instant.parse(values[4]));
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
                        .append(entry.lastRequested() == null ? "" : entry.lastRequested()).append('\n'));
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

    public void registerLocal(String revisionId, String targetDevice) {
        validateRevision(revisionId);
        Map<String, Entry> entries = readEntries();
        entries.putIfAbsent(key(revisionId, targetDevice),
                new Entry(revisionId, targetDevice, "pending", 0, null));
        writeEntries(entries);
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
                    .filter(id -> entries.containsKey(key(id, targetDevice))).toList();
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
        int attempts = prior.attempts() + 1;
        entries.put(key, new Entry(revisionId, targetDevice,
                attempts >= maxRequests ? "needs-attention" : "transfer-requested",
                attempts, observed));
        writeEntries(entries);
        recordEvent("transfer-requested", revisionId, observed, "target=" + targetDevice);
    }

    public void recordEvent(String kind, String revisionId, Instant observed, String detail) {
        validateRevision(revisionId);
        if (!List.of("transfer-requested", "kdeconnect-transfer-complete", "reasonnotes-imported").contains(kind)) {
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
