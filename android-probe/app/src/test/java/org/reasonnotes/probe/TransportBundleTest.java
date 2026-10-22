package org.reasonnotes.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class TransportBundleTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID NOTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String REVISION_ID = "6d7ca32a49c9a246fb7808ba06c541c1b3dacc7add6a874cf5aefb375e94025a";
    private static final String RECEIPT_ID = "5656b6c8e23ef55a998b960d770762d522381fd99fad86d5b595664aeed3e0f1";
    private static final String EXPECTED_NAME = "reasonnotes-bundle-v1-0374b8899e50de963b797ef5d45cabe850c72dcbbacd24f45808c474093a1f3b.json";
    private static final String EXPECTED =
            "{\"format\":\"reasonnotes-transport-bundle\",\"items\":["
            + "{\"envelope\":\"---\\nreasonnotes-receipt-format: 1\\nreceipt-id: "
            + RECEIPT_ID + "\\nnote-id: 11111111-1111-4111-8111-111111111111\\nrevision-id: "
            + REVISION_ID + "\\nacknowledging-device: android\\ndestination-device: linux\\n"
            + "imported-utc: 2026-10-22T03:04:06Z\\n---\\n\",\"id\":\"" + RECEIPT_ID
            + "\",\"kind\":\"receipt\"},{\"envelope\":\"---\\nreasonnotes-format: 1\\n"
            + "note-id: 11111111-1111-4111-8111-111111111111\\nrevision-id: " + REVISION_ID
            + "\\nparents: \\norigin-device: linux\\nmodified-utc: 2026-10-22T03:04:05Z\\n"
            + "deleted: false\\nbody-sha256: 1787b3e8ee79c0e1bf558994dbb836d8875d5763818dff36125f17f4a9b6a36b\\n"
            + "---\\n# Zażółć 📝\\n\\nLine \\\"two\\\" \\\\ slash\",\"id\":\"" + REVISION_ID
            + "\",\"kind\":\"revision\"}],\"version\":1}";

    private record Fixture(
            RevisionEnvelope revision,
            ReceiptEnvelope receipt,
            List<TransportBundle.Item> items) {}

    private Fixture fixture() {
        RevisionEnvelope revision = RevisionEnvelope.create(
                NOTE, List.of(), "linux", Instant.parse("2026-10-22T03:04:05Z"), false,
                "# Zażółć 📝\n\nLine \"two\" \\ slash");
        ReceiptEnvelope receipt = ReceiptEnvelope.create(
                NOTE, revision.revisionId(), "android", "linux",
                Instant.parse("2026-10-22T03:04:06Z"));
        return new Fixture(revision, receipt, List.of(
                new TransportBundle.Item("revision", revision.revisionId(), revision.serialize()),
                new TransportBundle.Item("receipt", receipt.receiptId(), receipt.serialize())));
    }

    @Test public void literalUnicodeFixtureMatchesDesktopCanonicalBytes() {
        Fixture fixture = fixture();
        assertEquals(REVISION_ID, fixture.revision().revisionId());
        assertEquals(RECEIPT_ID, fixture.receipt().receiptId());

        TransportBundle.Carrier carrier = TransportBundle.create(fixture.items());

        assertEquals(EXPECTED_NAME, carrier.filename());
        assertArrayEquals(EXPECTED.getBytes(StandardCharsets.UTF_8), carrier.payload());
        assertEquals(978, carrier.payload().length);
    }

    @Test public void parseReturnsValidatedCanonicalItemsAndAcceptsCollisionSuffix() {
        Fixture fixture = fixture();
        TransportBundle.Carrier created = TransportBundle.create(fixture.items());

        TransportBundle.Carrier parsed = TransportBundle.parse(
                created.payload(), created.filename().replace(".json", " (2).json"));

        assertEquals(List.of("receipt", "revision"),
                parsed.items().stream().map(TransportBundle.Item::kind).toList());
        assertEquals(List.of(RECEIPT_ID, REVISION_ID),
                parsed.items().stream().map(TransportBundle.Item::identity).toList());
    }

    @Test public void malformedNoncanonicalDuplicatePartialAndMismatchedPayloadsAreRejected()
            throws Exception {
        Fixture fixture = fixture();
        assertThrows(IllegalArgumentException.class, () -> TransportBundle.parse(
                new byte[] {(byte) 0xff}, "reasonnotes-bundle-v1-" + "0".repeat(64) + ".json"));

        String noncanonical = "{\"version\":1," + EXPECTED.substring(1, EXPECTED.length() - 13) + "}";
        byte[] reordered = noncanonical.getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> TransportBundle.parse(
                reordered, filename(reordered)));

        ArrayList<TransportBundle.Item> duplicate = new ArrayList<>(fixture.items());
        duplicate.add(fixture.items().get(0));
        assertThrows(IllegalArgumentException.class, () -> TransportBundle.create(duplicate));

        String mismatchedText = EXPECTED.replace(
                "\"id\":\"" + REVISION_ID + "\",\"kind\":\"revision\"",
                "\"id\":\"" + RECEIPT_ID + "\",\"kind\":\"revision\"");
        byte[] mismatched = mismatchedText.getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> TransportBundle.parse(
                mismatched, filename(mismatched)));

        TransportBundle.Carrier complete = TransportBundle.create(fixture.items());
        byte[] partial = java.util.Arrays.copyOf(complete.payload(), complete.payload().length - 1);
        assertThrows(IllegalArgumentException.class, () -> TransportBundle.parse(
                partial, complete.filename()));

        ArrayList<TransportBundle.Item> tooMany = new ArrayList<>();
        for (int index = 0; index < 257; index++) tooMany.add(fixture.items().get(0));
        assertThrows(IllegalArgumentException.class, () -> TransportBundle.create(tooMany));
    }

    @Test public void oneItemAndRepeatedWritesProduceExactlyOneDurableCarrier() throws Exception {
        Fixture fixture = fixture();
        File directory = temporary.newFolder("outbox");
        List<TransportBundle.Item> one = List.of(fixture.items().get(0));

        File written = TransportBundle.write(directory, one);
        TransportBundle.Carrier expected = TransportBundle.create(one);

        assertEquals(expected.filename(), written.getName());
        assertArrayEquals(expected.payload(), Files.readAllBytes(written.toPath()));
        assertEquals(1, directory.listFiles().length);
        assertTrue(written.isFile());

        File repeated = TransportBundle.write(directory, one);
        assertEquals(written, repeated);
        assertEquals(1, directory.listFiles().length);
    }

    private static String filename(byte[] payload) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
        return "reasonnotes-bundle-v1-" + hex + ".json";
    }
}
