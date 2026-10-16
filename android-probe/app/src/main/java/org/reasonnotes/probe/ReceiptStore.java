package org.reasonnotes.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ReceiptStore {
    public record DeliveryEntry(
            String receiptId,
            String revisionId,
            String targetDevice,
            String state,
            int attempts,
            Instant lastRequested) {}
    public record ImportOutcome(String kind, String receiptId, String revisionId) {}
    public record ReconcileReport(List<String> confirmed, List<String> errors) {}

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DEVICE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private final File inbox;
    private final File outbox;
    private final File deliveryFile;
    private final int maxRequests;

    public ReceiptStore(File root, int maxRequests) {
        if (maxRequests < 1) throw new IllegalArgumentException("maxRequests");
        File receipts = new File(root, "receipts");
        this.inbox = new File(receipts, "inbox");
        this.outbox = new File(receipts, "outbox");
        this.deliveryFile = new File(new File(root, "state"), "receipt-delivery.tsv");
        this.maxRequests = maxRequests;
        for (File directory : List.of(root, receipts, inbox, outbox, deliveryFile.getParentFile())) {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("cannot create receipt directory: " + directory);
            }
        }
        reconcileOutbox();
    }

    private static String key(String receiptId, String targetDevice) {
        return targetDevice + "\u0000" + receiptId;
    }

    private static void validateHash(String value) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid receipt ID");
        }
    }

    private static void validateDevice(String value) {
        if (value == null || !DEVICE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid device ID");
        }
    }

    private static void validateInstant(Instant value) {
        if (value == null || value.getNano() != 0) {
            throw new IllegalArgumentException("whole-second UTC time required");
        }
    }

    private static boolean durableWrite(File target, byte[] data, boolean replace) {
        try {
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) {
                throw new IOException("cannot create receipt parent directory");
            }
            if (!replace && target.isFile()) {
                if (!Arrays.equals(Files.readAllBytes(target.toPath()), data)) {
                    throw new IllegalArgumentException("stored receipt disagrees with receipt ID");
                }
                return false;
            }
            File temporary = File.createTempFile(
                    "." + target.getName() + ".", ".tmp", target.getParentFile());
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(data);
                    output.flush();
                    output.getFD().sync();
                }
                try {
                    Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            replace
                                    ? new StandardCopyOption[] {
                                        StandardCopyOption.ATOMIC_MOVE,
                                        StandardCopyOption.REPLACE_EXISTING
                                    }
                                    : new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE});
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            replace
                                    ? new StandardCopyOption[] {StandardCopyOption.REPLACE_EXISTING}
                                    : new StandardCopyOption[] {});
                }
                return true;
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot persist receipt", error);
        }
    }

    private Map<String, DeliveryEntry> readEntries() {
        LinkedHashMap<String, DeliveryEntry> result = new LinkedHashMap<>();
        if (!deliveryFile.isFile()) return result;
        try {
            for (String line : Files.readAllLines(deliveryFile.toPath(), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] values = line.split("\t", -1);
                if (values.length != 6) throw new IllegalArgumentException("receipt entry fields");
                validateHash(values[0]);
                validateHash(values[1]);
                validateDevice(values[2]);
                if (!List.of("pending", "transfer-requested", "needs-attention").contains(values[3])) {
                    throw new IllegalArgumentException("receipt delivery state");
                }
                int attempts = Integer.parseInt(values[4]);
                if (attempts < 0) throw new IllegalArgumentException("receipt attempts");
                Instant last = values[5].isEmpty() ? null : Instant.parse(values[5]);
                DeliveryEntry entry = new DeliveryEntry(
                        values[0], values[1], values[2], values[3], attempts, last);
                String key = key(entry.receiptId(), entry.targetDevice());
                if (result.put(key, entry) != null) {
                    throw new IllegalArgumentException("duplicate receipt delivery entry");
                }
            }
            return result;
        } catch (RuntimeException | IOException error) {
            throw new IllegalStateException("invalid receipt delivery state", error);
        }
    }

    private void writeEntries(Map<String, DeliveryEntry> entries) {
        StringBuilder value = new StringBuilder();
        entries.values().stream()
                .sorted(Comparator.comparing(DeliveryEntry::targetDevice)
                        .thenComparing(DeliveryEntry::receiptId))
                .forEach(entry -> value.append(entry.receiptId()).append('\t')
                        .append(entry.revisionId()).append('\t')
                        .append(entry.targetDevice()).append('\t')
                        .append(entry.state()).append('\t')
                        .append(entry.attempts()).append('\t')
                        .append(entry.lastRequested() == null ? "" : entry.lastRequested())
                        .append('\n'));
        durableWrite(deliveryFile, value.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    private void reconcileOutbox() {
        Map<String, DeliveryEntry> entries = readEntries();
        boolean changed = false;
        File[] targets = outbox.listFiles(File::isDirectory);
        if (targets != null) {
            Arrays.sort(targets, Comparator.comparing(File::getName));
            for (File target : targets) {
                File[] files = target.listFiles(file -> file.isFile() && file.getName().endsWith(".receipt.md"));
                if (files == null) continue;
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File file : files) {
                    try {
                        ReceiptEnvelope receipt = ReceiptEnvelope.parse(Files.readAllBytes(file.toPath()));
                        if (!target.getName().equals(receipt.destinationDevice())) {
                            throw new IllegalStateException("receipt outbox path has wrong destination");
                        }
                        String key = key(receipt.receiptId(), receipt.destinationDevice());
                        if (!entries.containsKey(key)) {
                            entries.put(key, new DeliveryEntry(
                                    receipt.receiptId(),
                                    receipt.revisionId(),
                                    receipt.destinationDevice(),
                                    "pending",
                                    0,
                                    null));
                            changed = true;
                        }
                    } catch (IOException error) {
                        throw new IllegalStateException("cannot read receipt outbox", error);
                    }
                }
            }
        }
        if (changed) writeEntries(entries);
    }

    /** Remove incoming/outgoing receipt records referencing erased history. */
    public void forgetRevisions(java.util.Set<String> ids) {
        if (ids.isEmpty()) return;
        Map<String, DeliveryEntry> entries = readEntries();
        if (entries.values().removeIf(entry -> ids.contains(entry.revisionId()))) {
            writeEntries(entries);
        }
        File[] inboxFiles = inbox.listFiles(file -> file.isFile()
                && file.getName().endsWith(".receipt.md"));
        if (inboxFiles != null) {
            for (File file : inboxFiles) forgetReceiptFile(file, ids);
        }
        File[] targets = outbox.listFiles(File::isDirectory);
        if (targets != null) {
            for (File target : targets) {
                File[] files = target.listFiles(file -> file.isFile()
                        && file.getName().endsWith(".receipt.md"));
                if (files != null) for (File file : files) forgetReceiptFile(file, ids);
            }
        }
    }

    private static void forgetReceiptFile(File file, java.util.Set<String> ids) {
        try {
            ReceiptEnvelope receipt = ReceiptEnvelope.parse(Files.readAllBytes(file.toPath()));
            if (ids.contains(receipt.revisionId())) Files.deleteIfExists(file.toPath());
        } catch (IllegalArgumentException error) {
            // An invalid unrelated receipt cannot block explicit note deletion.
        } catch (IOException error) {
            throw new IllegalStateException("cannot remove purged note receipt", error);
        }
    }

    public File inboxFile(String receiptId) {
        validateHash(receiptId);
        return new File(inbox, receiptId + ".receipt.md");
    }

    public File outboxFile(String receiptId) {
        validateHash(receiptId);
        File[] targets = outbox.listFiles(File::isDirectory);
        ArrayList<File> matches = new ArrayList<>();
        if (targets != null) {
            for (File target : targets) {
                File candidate = new File(target, receiptId + ".receipt.md");
                if (candidate.isFile()) matches.add(candidate);
            }
        }
        if (matches.size() != 1) throw new IllegalArgumentException("receipt is missing from outbox");
        return matches.get(0);
    }

    public ReceiptEnvelope queue(
            RevisionEnvelope revision, String acknowledgingDevice, Instant importedUtc) {
        validateDevice(acknowledgingDevice);
        validateInstant(importedUtc);
        Map<String, DeliveryEntry> entries = readEntries();
        List<DeliveryEntry> existing = entries.values().stream()
                .filter(entry -> entry.revisionId().equals(revision.revisionId()))
                .filter(entry -> entry.targetDevice().equals(revision.originDevice()))
                .toList();
        if (!existing.isEmpty()) {
            if (existing.size() != 1) throw new IllegalStateException("multiple receipts for one revision");
            try {
                return ReceiptEnvelope.parse(Files.readAllBytes(
                        outboxFile(existing.get(0).receiptId()).toPath()));
            } catch (IOException error) {
                throw new IllegalStateException("cannot read queued receipt", error);
            }
        }
        ReceiptEnvelope receipt = ReceiptEnvelope.create(
                revision.noteId(),
                revision.revisionId(),
                acknowledgingDevice,
                revision.originDevice(),
                importedUtc);
        File target = new File(new File(outbox, receipt.destinationDevice()), receipt.transportFilename());
        durableWrite(target, receipt.serialize(), false);
        entries.put(key(receipt.receiptId(), receipt.destinationDevice()), new DeliveryEntry(
                receipt.receiptId(),
                receipt.revisionId(),
                receipt.destinationDevice(),
                "pending",
                0,
                null));
        writeEntries(entries);
        return receipt;
    }

    public DeliveryEntry entry(String receiptId, String targetDevice) {
        validateHash(receiptId);
        validateDevice(targetDevice);
        return readEntries().get(key(receiptId, targetDevice));
    }

    public List<DeliveryEntry> entries(String targetDevice) {
        validateDevice(targetDevice);
        return readEntries().values().stream()
                .filter(entry -> entry.targetDevice().equals(targetDevice))
                .sorted(Comparator.comparing(DeliveryEntry::receiptId))
                .toList();
    }

    public List<String> eligible(
            String targetDevice, boolean retryUnconfirmed, List<String> forceIds) {
        validateDevice(targetDevice);
        Map<String, DeliveryEntry> entries = readEntries();
        if (!forceIds.isEmpty()) {
            return forceIds.stream().distinct().sorted()
                    .filter(id -> entries.containsKey(key(id, targetDevice)))
                    .toList();
        }
        return entries.values().stream()
                .filter(entry -> entry.targetDevice().equals(targetDevice))
                .filter(entry -> entry.state().equals("pending")
                        || (retryUnconfirmed
                        && entry.state().equals("transfer-requested")
                        && entry.attempts() < maxRequests))
                .map(DeliveryEntry::receiptId)
                .sorted()
                .toList();
    }

    public void recordRequested(String receiptId, String targetDevice, Instant observed) {
        validateHash(receiptId);
        validateDevice(targetDevice);
        validateInstant(observed);
        Map<String, DeliveryEntry> entries = readEntries();
        String key = key(receiptId, targetDevice);
        DeliveryEntry prior = entries.get(key);
        if (prior == null) throw new IllegalArgumentException("unregistered receipt");
        int attempts = prior.attempts() + 1;
        entries.put(key, new DeliveryEntry(
                receiptId,
                prior.revisionId(),
                targetDevice,
                attempts >= maxRequests ? "needs-attention" : "transfer-requested",
                attempts,
                observed));
        writeEntries(entries);
    }

    private static void validateConfirmation(
            ReceiptEnvelope receipt,
            ProbeRepository repository,
            DeliveryLedger ledger,
            String localDevice) {
        validateConfirmation(receipt, repository.revision(receipt.revisionId()),
                ledger, localDevice);
    }

    private static void validateConfirmation(
            ReceiptEnvelope receipt,
            RevisionEnvelope revision,
            DeliveryLedger ledger,
            String localDevice) {
        validateDevice(localDevice);
        if (!receipt.destinationDevice().equals(localDevice)) {
            throw new IllegalArgumentException("receipt is addressed to another device");
        }
        if (revision == null) throw new IllegalArgumentException("receipt names an unknown revision");
        if (!revision.noteId().equals(receipt.noteId())) {
            throw new IllegalArgumentException("receipt note and revision identities disagree");
        }
        DeliveryLedger.Entry entry = ledger.entry(
                receipt.revisionId(), receipt.acknowledgingDevice());
        if (entry == null) throw new IllegalArgumentException("receipt peer has no delivery entry");
        if (entry.attempts() < 1) {
            throw new IllegalArgumentException("revision was never requested to receipt peer");
        }
        if (entry.state().equals("confirmed")
                && !receipt.receiptId().equals(entry.receiptId())) {
            throw new IllegalArgumentException("confirmed revision has a different receipt");
        }
    }

    public ImportOutcome accept(
            byte[] data,
            ProbeRepository repository,
            DeliveryLedger ledger,
            String localDevice,
            String sourceName,
            Instant confirmedUtc) {
        if (sourceName == null) throw new IllegalArgumentException("source name");
        ReceiptEnvelope receipt = ReceiptEnvelope.parse(data);
        validateConfirmation(receipt, repository, ledger, localDevice);
        boolean created = durableWrite(inboxFile(receipt.receiptId()), receipt.serialize(), false);
        boolean changed = ledger.recordConfirmed(
                receipt.revisionId(),
                receipt.acknowledgingDevice(),
                receipt.receiptId(),
                confirmedUtc);
        return new ImportOutcome(
                created && changed ? "confirmed" : "duplicate",
                receipt.receiptId(),
                receipt.revisionId());
    }

    public ReconcileReport reconcileConfirmations(
            ProbeRepository repository,
            DeliveryLedger ledger,
            String localDevice,
            Instant confirmedUtc) {
        validateInstant(confirmedUtc);
        ArrayList<String> confirmed = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        File[] files = inbox.listFiles(file -> file.isFile() && file.getName().endsWith(".receipt.md"));
        if (files != null && files.length > 0) {
            // Recovery must validate every receipt, but it should not re-read
            // the entire revision history for each stored confirmation.
            Map<String, RevisionEnvelope> revisionById = new LinkedHashMap<>();
            for (RevisionEnvelope revision : repository.revisions()) {
                revisionById.put(revision.revisionId(), revision);
            }
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                try {
                    ReceiptEnvelope receipt = ReceiptEnvelope.parse(Files.readAllBytes(file.toPath()));
                    validateConfirmation(receipt, revisionById.get(receipt.revisionId()),
                            ledger, localDevice);
                    if (ledger.recordConfirmed(
                            receipt.revisionId(),
                            receipt.acknowledgingDevice(),
                            receipt.receiptId(),
                            confirmedUtc)) {
                        confirmed.add(receipt.revisionId());
                    }
                } catch (RuntimeException | IOException error) {
                    errors.add(file + ": " + error.getMessage());
                }
            }
        }
        return new ReconcileReport(List.copyOf(confirmed), List.copyOf(errors));
    }
}
