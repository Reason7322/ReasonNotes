package org.reasonnotes.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class StateEnvelopeTest {
    private static final UUID NOTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-24T12:30:00Z");

    @Test public void upsertAndDeletionAreCanonicalAndContentAddressed() {
        StateEnvelope upsert = StateEnvelope.createUpsert(
                NOTE, List.of("a".repeat(64)), "android", NOW, "Żółć", "Body\nline");
        assertEquals("upsert", upsert.operation());
        assertArrayEquals(upsert.serialize(), StateEnvelope.parse(upsert.serialize()).serialize());
        assertTrue(new String(upsert.serialize(), StandardCharsets.UTF_8).contains("\"body\":\"Body\\nline\""));

        StateEnvelope deletion = StateEnvelope.createDeletion(
                NOTE, List.of(upsert.changeId()), "android", NOW.plusSeconds(1));
        String deleted = new String(deletion.serialize(), StandardCharsets.UTF_8);
        assertEquals("delete", deletion.operation());
        assertFalse(deleted.contains("title"));
        assertFalse(deleted.contains("body"));
        assertArrayEquals(deletion.serialize(), StateEnvelope.parse(deletion.serialize()).serialize());
    }

    @Test public void malformedOrTamperedStateIsRejected() {
        StateEnvelope state = StateEnvelope.createUpsert(
                NOTE, List.of(), "linux", NOW, "Title", "Body");
        byte[] tampered = new String(state.serialize(), StandardCharsets.UTF_8)
                .replace("\"Body\"", "\"Other\"").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> StateEnvelope.parse(tampered));
        assertThrows(IllegalArgumentException.class,
                () -> StateEnvelope.createDeletion(NOTE, List.of("bad"), "linux", NOW));
    }

    @Test public void acknowledgmentNamesExactDurableChange() {
        StateAckEnvelope ack = StateAckEnvelope.create(
                NOTE, "b".repeat(64), "android", "linux", NOW);
        assertEquals("b".repeat(64), ack.acknowledgedChangeId());
        assertArrayEquals(ack.serialize(), StateAckEnvelope.parse(ack.serialize()).serialize());
        byte[] tampered = new String(ack.serialize(), StandardCharsets.UTF_8)
                .replace("\"destination_device\":\"linux\"",
                        "\"destination_device\":\"other\"")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> StateAckEnvelope.parse(tampered));
    }

    @Test public void sharedFixturesAreCanonical() throws Exception {
        byte[] upsert = resource("upsert.json");
        byte[] deletion = resource("delete.json");
        byte[] ack = resource("ack.json");
        assertArrayEquals(upsert, StateEnvelope.parse(upsert).serialize());
        assertArrayEquals(deletion, StateEnvelope.parse(deletion).serialize());
        assertArrayEquals(ack, StateAckEnvelope.parse(ack).serialize());
    }

    private static byte[] resource(String name) throws Exception {
        try (var input = StateEnvelopeTest.class.getResourceAsStream("/sync-v2/" + name)) {
            if (input == null) throw new AssertionError("missing fixture " + name);
            byte[] value = input.readAllBytes();
            return value[value.length - 1] == '\n'
                    ? java.util.Arrays.copyOf(value, value.length - 1) : value;
        }
    }
}
