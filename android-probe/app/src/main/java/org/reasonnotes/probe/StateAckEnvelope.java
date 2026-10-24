package org.reasonnotes.probe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact durable-import acknowledgment for a simplified v2 state change. */
public record StateAckEnvelope(
        UUID noteId,
        String acknowledgedChangeId,
        String ackId,
        String acknowledgingDevice,
        String destinationDevice,
        Instant importedUtc) {
    public static final String FORMAT = "reasonnotes-ack-v2";

    public StateAckEnvelope {
        Objects.requireNonNull(noteId, "noteId");
        StateEnvelope.validateHash(acknowledgedChangeId, "acknowledged change ID");
        StateEnvelope.validateHash(ackId, "acknowledgment ID");
        StateEnvelope.validateDevice(acknowledgingDevice);
        StateEnvelope.validateDevice(destinationDevice);
        if (acknowledgingDevice.equals(destinationDevice))
            throw new IllegalArgumentException("acknowledgment devices must differ");
        StateEnvelope.validateTime(importedUtc);
    }

    public static StateAckEnvelope create(
            UUID noteId, String changeId, String acknowledgingDevice,
            String destinationDevice, Instant importedUtc) {
        byte[] identity = canonical(false, null, noteId, changeId,
                acknowledgingDevice, destinationDevice, importedUtc);
        return new StateAckEnvelope(noteId, changeId, StateEnvelope.sha256(identity),
                acknowledgingDevice, destinationDevice, importedUtc);
    }

    public static StateAckEnvelope parse(byte[] data) {
        final JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("ack must be an object");
            object = parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("ack is not valid JSON", error);
        }
        if (!object.keySet().stream().toList().equals(List.of(
                "ack_id", "acknowledged_change_id", "acknowledging_device",
                "destination_device", "format", "imported_utc", "note_id")))
            throw new IllegalArgumentException("ack schema is not canonical");
        if (!FORMAT.equals(string(object, "format")))
            throw new IllegalArgumentException("unsupported ack format");
        UUID noteId;
        Instant importedUtc;
        try {
            noteId = UUID.fromString(string(object, "note_id"));
            importedUtc = Instant.parse(string(object, "imported_utc"));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid ack identity or time", error);
        }
        StateAckEnvelope ack = new StateAckEnvelope(
                noteId, string(object, "acknowledged_change_id"), string(object, "ack_id"),
                string(object, "acknowledging_device"), string(object, "destination_device"),
                importedUtc);
        String expected = StateEnvelope.sha256(canonical(false, null, noteId,
                ack.acknowledgedChangeId, ack.acknowledgingDevice,
                ack.destinationDevice, importedUtc));
        if (!expected.equals(ack.ackId)) throw new IllegalArgumentException("ack SHA-256 mismatch");
        if (!Arrays.equals(ack.serialize(), data))
            throw new IllegalArgumentException("ack is not canonical");
        return ack;
    }

    public byte[] serialize() {
        return canonical(true, ackId, noteId, acknowledgedChangeId,
                acknowledgingDevice, destinationDevice, importedUtc);
    }

    private static byte[] canonical(
            boolean signed, String ackId, UUID noteId, String changeId,
            String acknowledging, String destination, Instant importedUtc) {
        JsonObject value = new JsonObject();
        if (signed) value.addProperty("ack_id", ackId);
        value.addProperty("acknowledged_change_id", changeId);
        value.addProperty("acknowledging_device", acknowledging);
        value.addProperty("destination_device", destination);
        value.addProperty("format", FORMAT);
        value.addProperty("imported_utc", importedUtc.toString());
        value.addProperty("note_id", noteId.toString());
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("ack " + key + " must be a string");
        return value.getAsString();
    }
}
