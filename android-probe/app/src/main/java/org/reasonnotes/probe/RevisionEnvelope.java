package org.reasonnotes.probe;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class RevisionEnvelope {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DEVICE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final List<String> FIELDS = List.of(
            "reasonnotes-format", "note-id", "revision-id", "parents", "origin-device",
            "modified-utc", "deleted", "body-sha256");

    private final UUID noteId;
    private final String revisionId;
    private final List<String> parents;
    private final String originDevice;
    private final Instant modifiedUtc;
    private final boolean deleted;
    private final String bodySha256;
    private final String body;

    private RevisionEnvelope(
            UUID noteId, String revisionId, List<String> parents, String originDevice,
            Instant modifiedUtc, boolean deleted, String bodySha256, String body) {
        this.noteId = noteId;
        this.revisionId = revisionId;
        this.parents = List.copyOf(parents);
        this.originDevice = originDevice;
        this.modifiedUtc = modifiedUtc;
        this.deleted = deleted;
        this.bodySha256 = bodySha256;
        this.body = body;
    }

    public static RevisionEnvelope create(
            UUID noteId, List<String> parents, String originDevice, Instant modifiedUtc,
            boolean deleted, String body) {
        Objects.requireNonNull(noteId, "noteId");
        List<String> canonicalParents = validateParents(parents);
        validateDevice(originDevice);
        validateInstant(modifiedUtc);
        Objects.requireNonNull(body, "body");
        if (body.indexOf('\r') >= 0) throw new IllegalArgumentException("body contains carriage return");
        if (deleted && !body.isEmpty()) throw new IllegalArgumentException("tombstone body must be empty");
        String bodyHash = sha256(body.getBytes(StandardCharsets.UTF_8));
        byte[] unsigned = canonicalBytes(
                noteId, null, canonicalParents, originDevice, modifiedUtc, deleted, bodyHash, body);
        return new RevisionEnvelope(
                noteId, sha256(unsigned), canonicalParents, originDevice, modifiedUtc,
                deleted, bodyHash, body);
    }

    public static RevisionEnvelope parse(byte[] data) {
        Objects.requireNonNull(data, "data");
        for (byte value : data) if (value == '\r') throw new IllegalArgumentException("carriage return");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("invalid UTF-8", error);
        }
        if (!text.startsWith("---\n")) throw new IllegalArgumentException("missing opening marker");
        int closing = text.indexOf("\n---\n", 4);
        if (closing < 0) throw new IllegalArgumentException("missing closing marker");
        String header = text.substring(4, closing);
        String body = text.substring(closing + 5);
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : header.split("\n", -1)) {
            int separator = line.indexOf(": ");
            if (separator < 0) throw new IllegalArgumentException("malformed metadata");
            String key = line.substring(0, separator);
            if (!FIELDS.contains(key) || metadata.put(key, line.substring(separator + 2)) != null) {
                throw new IllegalArgumentException("unknown or repeated metadata");
            }
        }
        if (!new ArrayList<>(metadata.keySet()).equals(FIELDS)) {
            throw new IllegalArgumentException("noncanonical metadata fields");
        }
        if (!"1".equals(metadata.get("reasonnotes-format"))) {
            throw new IllegalArgumentException("unsupported format");
        }
        UUID noteId;
        Instant modified;
        try {
            noteId = UUID.fromString(metadata.get("note-id"));
            modified = Instant.parse(metadata.get("modified-utc"));
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw new IllegalArgumentException("invalid identity or timestamp", error);
        }
        if (!noteId.toString().equals(metadata.get("note-id"))) throw new IllegalArgumentException("UUID");
        validateInstant(modified);
        if (!modified.toString().equals(metadata.get("modified-utc"))) throw new IllegalArgumentException("timestamp");
        String revisionId = metadata.get("revision-id");
        String bodyHash = metadata.get("body-sha256");
        if (!HASH.matcher(revisionId).matches() || !HASH.matcher(bodyHash).matches()) {
            throw new IllegalArgumentException("invalid hash");
        }
        List<String> parents = metadata.get("parents").isEmpty()
                ? List.of() : Arrays.asList(metadata.get("parents").split(",", -1));
        parents = validateParents(parents);
        if (!String.join(",", parents).equals(metadata.get("parents"))) {
            throw new IllegalArgumentException("noncanonical parents");
        }
        String device = metadata.get("origin-device");
        validateDevice(device);
        String deletedValue = metadata.get("deleted");
        if (!deletedValue.equals("true") && !deletedValue.equals("false")) {
            throw new IllegalArgumentException("invalid deletion flag");
        }
        boolean deleted = Boolean.parseBoolean(deletedValue);
        if (deleted && !body.isEmpty()) throw new IllegalArgumentException("tombstone body");
        if (!sha256(body.getBytes(StandardCharsets.UTF_8)).equals(bodyHash)) {
            throw new IllegalArgumentException("body SHA-256 mismatch");
        }
        String expected = sha256(canonicalBytes(
                noteId, null, parents, device, modified, deleted, bodyHash, body));
        if (!expected.equals(revisionId)) throw new IllegalArgumentException("revision SHA-256 mismatch");
        RevisionEnvelope result = new RevisionEnvelope(
                noteId, revisionId, parents, device, modified, deleted, bodyHash, body);
        if (!Arrays.equals(result.serialize(), data)) throw new IllegalArgumentException("noncanonical envelope");
        return result;
    }

    private static List<String> validateParents(List<String> values) {
        Objects.requireNonNull(values, "parents");
        ArrayList<String> result = new ArrayList<>(values);
        for (String value : result) {
            if (value == null || !HASH.matcher(value).matches()) throw new IllegalArgumentException("parent hash");
        }
        if (result.stream().distinct().count() != result.size()) throw new IllegalArgumentException("duplicate parent");
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private static void validateDevice(String device) {
        if (device == null || !DEVICE.matcher(device).matches()) throw new IllegalArgumentException("device ID");
    }

    private static void validateInstant(Instant instant) {
        if (instant == null || instant.getNano() != 0) throw new IllegalArgumentException("whole-second UTC time required");
    }

    private static byte[] canonicalBytes(
            UUID noteId, String revisionId, List<String> parents, String device,
            Instant modified, boolean deleted, String bodyHash, String body) {
        StringBuilder value = new StringBuilder("---\nreasonnotes-format: 1\nnote-id: ").append(noteId).append('\n');
        if (revisionId != null) value.append("revision-id: ").append(revisionId).append('\n');
        value.append("parents: ").append(String.join(",", parents)).append('\n')
                .append("origin-device: ").append(device).append('\n')
                .append("modified-utc: ").append(modified).append('\n')
                .append("deleted: ").append(deleted).append('\n')
                .append("body-sha256: ").append(bodyHash).append("\n---\n")
                .append(body);
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public byte[] serialize() {
        return canonicalBytes(noteId, revisionId, parents, originDevice, modifiedUtc, deleted, bodySha256, body);
    }
    public UUID noteId() { return noteId; }
    public String revisionId() { return revisionId; }
    public List<String> parents() { return parents; }
    public String originDevice() { return originDevice; }
    public Instant modifiedUtc() { return modifiedUtc; }
    public boolean deleted() { return deleted; }
    public String bodySha256() { return bodySha256; }
    public String body() { return body; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof RevisionEnvelope that)) return false;
        return noteId.equals(that.noteId) && revisionId.equals(that.revisionId)
                && parents.equals(that.parents) && originDevice.equals(that.originDevice)
                && modifiedUtc.equals(that.modifiedUtc) && deleted == that.deleted
                && bodySha256.equals(that.bodySha256) && body.equals(that.body);
    }
    @Override public int hashCode() { return revisionId.hashCode(); }
}

