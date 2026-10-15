package org.reasonnotes.probe;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One immutable, content-addressed ZIP carrier for a multi-file KDE Connect share.
 *
 * KDE Connect's Android share activity is asynchronous: launching it is not
 * transfer completion. Packaging first ensures it needs to open only one
 * FileProvider URI. Receipt confirmation still happens per revision.
 */
public final class TransferBundle {
    public static final String PREFIX = "reasonnotes-bundle-";
    public static final int MAX_ITEMS = 256;
    public static final long MAX_ITEM_BYTES = 16L * 1024 * 1024;
    // Leave space below Fedora's 64 MiB archive limit for ZIP framing overhead.
    public static final long MAX_TOTAL_BYTES = 60L * 1024 * 1024;

    private TransferBundle() {}

    public static File create(File outgoingDirectory, List<SyncCoordinator.OutgoingFile> outgoing)
            throws IOException {
        if (outgoing == null || outgoing.size() < 2 || outgoing.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("bundle requires 2 to 256 files");
        }
        List<SyncCoordinator.OutgoingFile> sorted = outgoing.stream()
                .sorted(Comparator.comparing(TransferBundle::entryName)).toList();
        long total = 0;
        Set<String> names = new HashSet<>();
        for (SyncCoordinator.OutgoingFile item : sorted) {
            String name = entryName(item);
            if (!names.add(name)) throw new IllegalArgumentException("duplicate bundle entry " + name);
            Path source = item.file().toPath();
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("missing or unsafe source file: " + name);
            }
            long length = Files.size(source);
            if (length > MAX_ITEM_BYTES || (total += length) > MAX_TOTAL_BYTES) {
                throw new IOException("bundle content exceeds size limit");
            }
        }

        Path directory = outgoingDirectory.toPath();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "reasonnotes-bundle-", ".part");
        try {
            try (OutputStream output = Files.newOutputStream(temporary);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                for (SyncCoordinator.OutgoingFile item : sorted) {
                    ZipEntry entry = new ZipEntry(entryName(item));
                    entry.setTime(315532800000L); // fixed 1980 timestamp
                    zip.putNextEntry(entry);
                    try (InputStream input = Files.newInputStream(item.file().toPath())) {
                        byte[] buffer = new byte[8192];
                        long written = 0;
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if ((written += count) > MAX_ITEM_BYTES) {
                                throw new IOException("source changed while building bundle");
                            }
                            zip.write(buffer, 0, count);
                        }
                    }
                    zip.closeEntry();
                }
            }
            String hash = sha256(temporary);
            Path destination = directory.resolve(PREFIX + hash + ".zip");
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return destination.toFile();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String entryName(SyncCoordinator.OutgoingFile item) {
        String id = item.identity();
        if (id.length() != 64) throw new IllegalArgumentException("invalid transfer identity");
        for (int i = 0; i < id.length(); i++) {
            char digit = id.charAt(i);
            if (!((digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f'))) {
                throw new IllegalArgumentException("invalid transfer identity");
            }
        }
        return id + (item.kind() == SyncCoordinator.FileKind.RECEIPT ? ".receipt.md" : ".md");
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            char[] digits = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(digits[(value & 255) >>> 4]).append(digits[value & 15]);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
