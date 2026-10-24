package org.reasonnotes.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Private cache of exchange documents whose bytes were already validated. */
final class ProcessedExchangeIndex {
    private record Fingerprint(long modifiedUtc, long size) {}
    private final File file;
    private final Map<String, Fingerprint> entries = new LinkedHashMap<>();

    ProcessedExchangeIndex(File file) {
        this.file = file;
        if (!file.isFile()) return;
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 3) throw new IllegalArgumentException("field count");
                String name = new String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8);
                entries.put(name, new Fingerprint(Long.parseLong(fields[1]), Long.parseLong(fields[2])));
            }
        } catch (Exception error) {
            throw new IllegalStateException("invalid processed exchange index", error);
        }
    }

    boolean contains(String name, long modifiedUtc, long size) {
        if (modifiedUtc <= 0 || size < 0) return false;
        return new Fingerprint(modifiedUtc, size).equals(entries.get(name));
    }

    void record(String name, long modifiedUtc, long size) {
        if (modifiedUtc <= 0 || size < 0) return;
        entries.put(name, new Fingerprint(modifiedUtc, size));
    }

    void save() {
        StringBuilder data = new StringBuilder();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    entry.getKey().getBytes(StandardCharsets.UTF_8));
            data.append(encoded).append('\t').append(entry.getValue().modifiedUtc())
                    .append('\t').append(entry.getValue().size()).append('\n');
        });
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("index directory");
        try {
            File temporary = File.createTempFile(".processed-", ".tmp", parent);
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(data.toString().getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    output.getFD().sync();
                }
                Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
        } catch (Exception error) {
            throw new IllegalStateException("cannot persist processed exchange index", error);
        }
    }
}
