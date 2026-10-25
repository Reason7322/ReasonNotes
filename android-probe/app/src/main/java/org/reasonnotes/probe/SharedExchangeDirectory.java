package org.reasonnotes.probe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/** Reads only the dedicated shared directory after Android grants All files access. */
final class SharedExchangeDirectory {
    private SharedExchangeDirectory() {}

    static ExchangeDirectory.ScanReport scan(
            File directory,
            File processedFile,
            CurrentNoteStore store,
            String localDevice,
            IntConsumer progress) {
        if (!directory.isDirectory()) {
            return report(0, 0, 0, 0, 0, 0, 0,
                    List.of("shared exchange directory is unavailable"));
        }
        File[] listed = directory.listFiles(File::isFile);
        if (listed == null) {
            return report(0, 0, 0, 0, 0, 0, 0,
                    List.of("cannot list shared exchange directory"));
        }
        Arrays.sort(listed, Comparator
                .comparingInt((File file) -> ExchangeDirectory.priority(file.getName()))
                .thenComparing(Comparator.comparingLong(File::lastModified).reversed())
                .thenComparing(File::getName));

        int imported = 0;
        int duplicates = 0;
        int confirmed = 0;
        int queued = 0;
        int rejected = 0;
        int ignored = 0;
        ArrayList<String> errors = new ArrayList<>();
        ProcessedExchangeIndex processed = new ProcessedExchangeIndex(processedFile);
        for (File file : listed) {
            String name = file.getName();
            long modified = file.lastModified();
            long size = file.length();
            if (processed.contains(name, modified, size)) continue;
            boolean successful = false;
            try {
                if (!ExchangeDirectory.isCandidateFilename(name)) {
                    ignored++;
                    successful = true;
                    continue;
                }
                byte[] data = Files.readAllBytes(file.toPath());
                CurrentTransport.ScanReport result;
                if (TransportBundle.isBundleFilename(name)) {
                    result = CurrentTransport.applyBundle(data, name, store, localDevice);
                } else if (ExchangeDirectory.classify(data) == ExchangeDirectory.Kind.REVISION) {
                    result = CurrentTransport.applyLegacyRevision(data, store, localDevice);
                } else {
                    ignored++;
                    successful = true;
                    continue;
                }
                imported += result.imported();
                duplicates += result.duplicates();
                confirmed += result.confirmed();
                queued += result.acknowledgmentsQueued();
                rejected += result.errors().size();
                errors.addAll(result.errors().stream()
                        .map(error -> name + ": " + error).toList());
                if (result.imported() > 0) progress.accept(imported);
                successful = result.errors().isEmpty();
            } catch (IOException | RuntimeException error) {
                rejected++;
                errors.add(name + ": " + error.getMessage());
            } finally {
                if (successful) processed.record(name, modified, size);
            }
        }
        processed.save();
        return report(imported, duplicates, confirmed, queued, rejected, 0, ignored, errors);
    }

    private static ExchangeDirectory.ScanReport report(
            int imported, int duplicates, int confirmed, int queued,
            int rejectedRevisions, int rejectedReceipts, int ignored,
            List<String> errors) {
        return new ExchangeDirectory.ScanReport(
                imported, duplicates, confirmed, 0, queued,
                rejectedRevisions, rejectedReceipts, ignored, List.copyOf(errors));
    }
}
