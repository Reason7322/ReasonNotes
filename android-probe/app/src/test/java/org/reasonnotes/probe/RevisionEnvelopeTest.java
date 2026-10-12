package org.reasonnotes.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class RevisionEnvelopeTest {
    private static final UUID NOTE_ID = UUID.fromString("12345678-1234-5678-9234-567812345678");
    private static final String PARENT_ONE = "1".repeat(64);
    private static final String PARENT_TWO = "a".repeat(64);
    private static final String BODY = "# Café\n\nこんにちは 👋\n";
    private static final String BODY_HASH = "7ce18b0fadcb42d61eccde4d97e5c242322dd652b5a72bd4831c1862ea5e8590";
    private static final String REVISION_ID = "936fb0af2f314bdef568a6575dc6b0dfe1fe9dfc51e56cd30fae9920d3578aef";

    @Test
    public void matchesPythonCanonicalUnicodeVector() {
        RevisionEnvelope revision = RevisionEnvelope.create(
                NOTE_ID,
                List.of(PARENT_TWO, PARENT_ONE),
                "linux",
                Instant.parse("2026-10-12T09:10:11Z"),
                false,
                BODY);
        String expected = "---\nreasonnotes-format: 1\nnote-id: " + NOTE_ID
                + "\nrevision-id: " + REVISION_ID
                + "\nparents: " + PARENT_ONE + "," + PARENT_TWO
                + "\norigin-device: linux\nmodified-utc: 2026-10-12T09:10:11Z"
                + "\ndeleted: false\nbody-sha256: " + BODY_HASH + "\n---\n" + BODY;

        assertEquals(REVISION_ID, revision.revisionId());
        assertEquals(BODY_HASH, revision.bodySha256());
        assertEquals(List.of(PARENT_ONE, PARENT_TWO), revision.parents());
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), revision.serialize());
        assertEquals(revision, RevisionEnvelope.parse(revision.serialize()));
    }

    @Test
    public void revisionHashCoversParentsMetadataAndBody() {
        RevisionEnvelope original = RevisionEnvelope.create(
                NOTE_ID, List.of(PARENT_ONE), "linux", Instant.parse("2026-10-12T09:10:11Z"), false, BODY);
        RevisionEnvelope changed = RevisionEnvelope.create(
                NOTE_ID, List.of(PARENT_TWO), "android", Instant.parse("2026-10-12T09:10:12Z"), false, BODY + "changed");
        assertNotEquals(original.revisionId(), changed.revisionId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTruncatedBody() {
        RevisionEnvelope valid = RevisionEnvelope.create(
                NOTE_ID, List.of(), "linux", Instant.parse("2026-10-12T09:10:11Z"), false, BODY);
        byte[] bytes = valid.serialize();
        RevisionEnvelope.parse(java.util.Arrays.copyOf(bytes, bytes.length - 2));
    }
}

