package org.reasonnotes.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class TransferBundleTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String REVISION = "a".repeat(64);
    private static final String RECEIPT = "b".repeat(64);
    private static final String THIRD = "c".repeat(64);

    private SyncCoordinator.OutgoingFile outgoing(String id, SyncCoordinator.FileKind kind, String text)
            throws Exception {
        File file = temporary.newFile(id + (kind == SyncCoordinator.FileKind.RECEIPT
                ? ".receipt.md" : ".md"));
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return new SyncCoordinator.OutgoingFile(kind, id, file);
    }

    private String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder b = new StringBuilder(64);
        for (byte value : digest) b.append(String.format("%02x", value & 0xff));
        return b.toString();
    }

    @Test
    public void batchesMultipleRevisionsAndReceiptsIntoOneContentAddressedZip() throws Exception {
        File dir = temporary.newFolder("outgoing");
        var receipt = outgoing(RECEIPT, SyncCoordinator.FileKind.RECEIPT, "receipt bytes");
        var revision = outgoing(REVISION, SyncCoordinator.FileKind.REVISION, "revision bytes");

        File zip = TransferBundle.create(dir, List.of(receipt, revision));

        assertTrue(zip.isFile());
        assertEquals("reasonnotes-bundle-" + sha256(Files.readAllBytes(zip.toPath())) + ".zip",
                zip.getName());
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(entry.getName(),
                        new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(Map.of(REVISION + ".md", "revision bytes",
                            RECEIPT + ".receipt.md", "receipt bytes"), entries);
        assertEquals("revision bytes", new String(Files.readAllBytes(revision.file().toPath()), StandardCharsets.UTF_8));
        assertEquals("receipt bytes", new String(Files.readAllBytes(receipt.file().toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void oppositeInputOrderingProducesIdenticalZip() throws Exception {
        File dir = temporary.newFolder("outgoing");
        var first = outgoing(REVISION, SyncCoordinator.FileKind.REVISION, "first");
        var second = outgoing(RECEIPT, SyncCoordinator.FileKind.RECEIPT, "second");
        File a = TransferBundle.create(dir, List.of(first, second));
        byte[] original = Files.readAllBytes(a.toPath());
        File b = TransferBundle.create(dir, List.of(second, first));
        assertEquals(a.getName(), b.getName());
        assertArrayEquals(original, Files.readAllBytes(b.toPath()));
    }

    @Test
    public void rejectsMissingOrSymlinkSourceWithoutCreatingBundle() throws Exception {
        File dir = temporary.newFolder("outgoing");
        var real = outgoing(REVISION, SyncCoordinator.FileKind.REVISION, "revision");
        File missing = new File(temporary.getRoot(), "missing.md");
        var absent = new SyncCoordinator.OutgoingFile(SyncCoordinator.FileKind.RECEIPT,
                RECEIPT, missing);
        assertThrows(java.io.IOException.class,
                () -> TransferBundle.create(dir, List.of(real, absent)));
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void rejectsDuplicateAndMalformedIdentifiers() throws Exception {
        File dir = temporary.newFolder("outgoing");
        var first = outgoing(REVISION, SyncCoordinator.FileKind.REVISION, "first");
        assertThrows(IllegalArgumentException.class,
                () -> TransferBundle.create(dir, List.of(first, first)));
        var bad = new SyncCoordinator.OutgoingFile(SyncCoordinator.FileKind.RECEIPT,
                "../bad", first.file());
        assertThrows(IllegalArgumentException.class,
                () -> TransferBundle.create(dir, List.of(first, bad)));
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void rejectsMoreThanTwoHundredFiftySixItems() throws Exception {
        File dir = temporary.newFolder("outgoing");
        var first = outgoing(REVISION, SyncCoordinator.FileKind.REVISION, "first");
        ArrayList<SyncCoordinator.OutgoingFile> entries = new ArrayList<>();
        for (int i = 0; i < 257; i++) entries.add(first);
        assertThrows(IllegalArgumentException.class, () -> TransferBundle.create(dir, entries));
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void twentyOneReceiptFilesUseExactlyOneCarrier() throws Exception {
        File dir = temporary.newFolder("outgoing");
        ArrayList<SyncCoordinator.OutgoingFile> files = new ArrayList<>();
        for (int index = 1; index <= 21; index++) {
            String id = String.format("%064x", index);
            files.add(outgoing(id, SyncCoordinator.FileKind.RECEIPT,
                    "receipt " + index));
        }
        File zip = TransferBundle.create(dir, files);
        int members = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            while (input.getNextEntry() != null) {
                members++;
                input.closeEntry();
            }
        }
        assertEquals(21, members);
        assertEquals(1, dir.listFiles().length);
    }

    @Test
    public void successfulArchiveIsStableAndContainsNoPendingPartFile() throws Exception {
        File dir = temporary.newFolder("outgoing");
        var a = outgoing(REVISION, SyncCoordinator.FileKind.REVISION, "a");
        var b = outgoing(THIRD, SyncCoordinator.FileKind.REVISION, "c");
        File result = TransferBundle.create(dir, List.of(a, b));
        assertEquals(1, dir.listFiles().length);
        assertFalse(result.getName().endsWith(".part"));
    }
}
