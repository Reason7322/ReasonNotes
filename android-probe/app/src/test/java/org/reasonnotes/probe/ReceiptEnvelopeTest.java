package org.reasonnotes.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.Test;

public final class ReceiptEnvelopeTest {
    private static final UUID NOTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String REVISION = "a".repeat(64);
    private static final String RECEIPT = "89dc65423853a87ed22b14561ed9535a50f7fd9bb9a1f5fd54263af96b18650d";
    private static final byte[] CANONICAL = ("---\n"
            + "reasonnotes-receipt-format: 1\n"
            + "receipt-id: " + RECEIPT + "\n"
            + "note-id: " + NOTE + "\n"
            + "revision-id: " + REVISION + "\n"
            + "acknowledging-device: android\n"
            + "destination-device: linux\n"
            + "imported-utc: 2026-10-12T21:00:00Z\n"
            + "---\n").getBytes(StandardCharsets.UTF_8);

    private ReceiptEnvelope receipt() {
        return ReceiptEnvelope.create(
                NOTE, REVISION, "android", "linux", Instant.parse("2026-10-12T21:00:00Z"));
    }

    @Test
    public void canonicalBytesAndIdentityMatchPythonVector() {
        ReceiptEnvelope receipt = receipt();

        assertEquals(RECEIPT, receipt.receiptId());
        assertArrayEquals(CANONICAL, receipt.serialize());
        assertEquals(receipt, ReceiptEnvelope.parse(CANONICAL));
        assertEquals(RECEIPT + ".receipt.md", receipt.transportFilename());
    }

    @Test
    public void identityCoversNoteRevisionPeersAndTime() {
        ReceiptEnvelope original = receipt();

        assertNotEquals(original.receiptId(), ReceiptEnvelope.create(
                UUID.fromString("22222222-2222-4222-8222-222222222222"), REVISION,
                "android", "linux", original.importedUtc()).receiptId());
        assertNotEquals(original.receiptId(), ReceiptEnvelope.create(
                NOTE, "b".repeat(64), "android", "linux", original.importedUtc()).receiptId());
        assertNotEquals(original.receiptId(), ReceiptEnvelope.create(
                NOTE, REVISION, "phone", "linux", original.importedUtc()).receiptId());
        assertNotEquals(original.receiptId(), ReceiptEnvelope.create(
                NOTE, REVISION, "android", "desktop", original.importedUtc()).receiptId());
        assertNotEquals(original.receiptId(), ReceiptEnvelope.create(
                NOTE, REVISION, "android", "linux", original.importedUtc().plusSeconds(1)).receiptId());
    }

    @Test
    public void corruptTruncatedAndNoncanonicalReceiptsAreRejected() {
        String canonical = new String(CANONICAL, StandardCharsets.UTF_8);
        byte[][] invalid = {
                canonical.replace(RECEIPT, "0".repeat(64)).getBytes(StandardCharsets.UTF_8),
                canonical.substring(0, canonical.length() - 4).getBytes(StandardCharsets.UTF_8),
                canonical.replace("note-id: ", "unexpected: value\nnote-id: ").getBytes(StandardCharsets.UTF_8),
                (canonical + "body\n").getBytes(StandardCharsets.UTF_8),
                canonical.replaceFirst("---\\n", "---\\r\\n").getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xff}
        };

        for (byte[] value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> ReceiptEnvelope.parse(value));
        }
    }
}
