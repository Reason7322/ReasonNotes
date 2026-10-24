package org.reasonnotes.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Canonical current-state record used by simplified synchronization v2. */
public record StateEnvelope(
        UUID noteId,
        String operation,
        String changeId,
        List<String> parentChangeIds,
        String originDevice,
        Instant modifiedUtc,
        String title,
        String body) {
    public static final String FORMAT = "reasonnotes-state-v2";
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DEVICE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public StateEnvelope {
        Objects.requireNonNull(noteId, "noteId");
        if (!operation.equals("upsert") && !operation.equals("delete"))
            throw new IllegalArgumentException("unsupported state operation");
        validateHash(changeId, "change ID");
        parentChangeIds = canonicalParents(parentChangeIds);
        validateDevice(originDevice);
        validateTime(modifiedUtc);
        if (operation.equals("upsert")) {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(body, "body");
        } else if (title != null || body != null) {
            throw new IllegalArgumentException("deletion must not contain note content");
        }
    }

    public static StateEnvelope createUpsert(
            UUID noteId, List<String> parents, String originDevice, Instant modifiedUtc,
            String title, String body) {
        return create(noteId, "upsert", parents, originDevice, modifiedUtc,
                Objects.requireNonNull(title), Objects.requireNonNull(body));
    }

    public static StateEnvelope createDeletion(
            UUID noteId, List<String> parents, String originDevice, Instant modifiedUtc) {
        return create(noteId, "delete", parents, originDevice, modifiedUtc, null, null);
    }

    private static StateEnvelope create(
            UUID noteId, String operation, List<String> parents, String originDevice,
            Instant modifiedUtc, String title, String body) {
        List<String> canonicalParents = canonicalParents(parents);
        validateDevice(originDevice);
        validateTime(modifiedUtc);
        String id = sha256(canonical(false, null, noteId, operation, canonicalParents,
                originDevice, modifiedUtc, title, body));
        return new StateEnvelope(noteId, operation, id, canonicalParents,
                originDevice, modifiedUtc, title, body);
    }

    public static StateEnvelope parse(byte[] data) {
        Objects.requireNonNull(data, "data");
        final JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(
                    new String(data, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("state must be an object");
            object = parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("state is not valid JSON", error);
        }
        String operation = string(object, "operation");
        List<String> expectedKeys = operation.equals("upsert")
                ? List.of("body", "change_id", "format", "modified_utc", "note_id",
                        "operation", "origin_device", "parent_change_ids", "title")
                : List.of("change_id", "format", "modified_utc", "note_id", "operation",
                        "origin_device", "parent_change_ids");
        if (!object.keySet().stream().toList().equals(expectedKeys))
            throw new IllegalArgumentException("state schema is not canonical");
        if (!FORMAT.equals(string(object, "format")))
            throw new IllegalArgumentException("unsupported state format");
        UUID noteId;
        Instant modifiedUtc;
        try {
            noteId = UUID.fromString(string(object, "note_id"));
            modifiedUtc = Instant.parse(string(object, "modified_utc"));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid state identity or time", error);
        }
        if (!noteId.toString().equals(string(object, "note_id")))
            throw new IllegalArgumentException("state UUID is not canonical");
        JsonElement rawParents = object.get("parent_change_ids");
        if (rawParents == null || !rawParents.isJsonArray())
            throw new IllegalArgumentException("state parents must be an array");
        ArrayList<String> parents = new ArrayList<>();
        for (JsonElement item : rawParents.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("state parent must be a string");
            parents.add(item.getAsString());
        }
        String changeId = string(object, "change_id");
        String origin = string(object, "origin_device");
        String title = operation.equals("upsert") ? string(object, "title") : null;
        String body = operation.equals("upsert") ? string(object, "body") : null;
        StateEnvelope state = new StateEnvelope(
                noteId, operation, changeId, parents, origin, modifiedUtc, title, body);
        String expected = sha256(canonical(false, null, noteId, operation,
                state.parentChangeIds, origin, modifiedUtc, title, body));
        if (!expected.equals(changeId)) throw new IllegalArgumentException("state SHA-256 mismatch");
        if (!Arrays.equals(state.serialize(), data))
            throw new IllegalArgumentException("state is not canonical");
        return state;
    }

    public byte[] serialize() {
        return canonical(true, changeId, noteId, operation, parentChangeIds,
                originDevice, modifiedUtc, title, body);
    }

    private static byte[] canonical(
            boolean signed, String changeId, UUID noteId, String operation,
            List<String> parents, String origin, Instant modifiedUtc, String title, String body) {
        JsonObject value = new JsonObject();
        if (operation.equals("upsert")) value.addProperty("body", body);
        if (signed) value.addProperty("change_id", changeId);
        value.addProperty("format", FORMAT);
        value.addProperty("modified_utc", modifiedUtc.toString());
        value.addProperty("note_id", noteId.toString());
        value.addProperty("operation", operation);
        value.addProperty("origin_device", origin);
        JsonArray parentValues = new JsonArray();
        parents.forEach(parentValues::add);
        value.add("parent_change_ids", parentValues);
        if (operation.equals("upsert")) value.addProperty("title", title);
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> canonicalParents(List<String> values) {
        if (values == null || values.size() > 2)
            throw new IllegalArgumentException("state must have zero to two parents");
        ArrayList<String> result = new ArrayList<>(values);
        result.forEach(value -> validateHash(value, "parent change ID"));
        result.sort(String::compareTo);
        if (new HashSet<>(result).size() != result.size())
            throw new IllegalArgumentException("state parents must be unique");
        return List.copyOf(result);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("state " + key + " must be a string");
        return value.getAsString();
    }

    static void validateHash(String value, String label) {
        if (value == null || !HASH.matcher(value).matches())
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 value");
    }

    static void validateDevice(String value) {
        if (value == null || !DEVICE.matcher(value).matches())
            throw new IllegalArgumentException("invalid device ID");
    }

    static void validateTime(Instant value) {
        if (value == null || value.getNano() != 0)
            throw new IllegalArgumentException("state time must use whole UTC seconds");
    }

    static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
