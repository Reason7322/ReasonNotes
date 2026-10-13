package org.reasonnotes.probe;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ReceiptEnvelope(
        UUID noteId,
        String revisionId,
        String receiptId,
        String acknowledgingDevice,
        String destinationDevice,
        Instant importedUtc) {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DEVICE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final List<String> FIELDS = List.of(
            "reasonnotes-receipt-format", "receipt-id", "note-id", "revision-id",
            "acknowledging-device", "destination-device", "imported-utc");

    public ReceiptEnvelope {
        Objects.requireNonNull(noteId, "noteId");
        validateHash(revisionId, "revision ID");
        validateHash(receiptId, "receipt ID");
        validateDevice(acknowledgingDevice, "acknowledging device");
        validateDevice(destinationDevice, "destination device");
        if (acknowledgingDevice.equals(destinationDevice)) {
            throw new IllegalArgumentException("receipt devices must be distinct");
        }
        validateInstant(importedUtc);
    }

    public static ReceiptEnvelope create(
            UUID noteId,
            String revisionId,
            String acknowledgingDevice,
            String destinationDevice,
            Instant importedUtc) {
        Objects.requireNonNull(noteId, "noteId");
        validateHash(revisionId, "revision ID");
        validateDevice(acknowledgingDevice, "acknowledging device");
        validateDevice(destinationDevice, "destination device");
        if (acknowledgingDevice.equals(destinationDevice)) {
            throw new IllegalArgumentException("receipt devices must be distinct");
        }
        validateInstant(importedUtc);
        String receiptId = sha256(identityBytes(
                noteId, revisionId, acknowledgingDevice, destinationDevice, importedUtc));
        return new ReceiptEnvelope(
                noteId, revisionId, receiptId, acknowledgingDevice, destinationDevice, importedUtc);
    }

    public static ReceiptEnvelope parse(byte[] data) {
        Objects.requireNonNull(data, "data");
        for (byte value : data) {
            if (value == '\r') throw new IllegalArgumentException("carriage returns are not canonical");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("receipt is not valid UTF-8", error);
        }
        if (!text.startsWith("---\n")) throw new IllegalArgumentException("missing opening receipt marker");
        int closing = text.indexOf("\n---\n", 4);
        if (closing < 0) throw new IllegalArgumentException("missing closing receipt marker");
        if (closing + 5 != text.length()) throw new IllegalArgumentException("receipt body must be empty");
        String header = text.substring(4, closing);
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : header.split("\n", -1)) {
            int separator = line.indexOf(": ");
            if (separator < 0) throw new IllegalArgumentException("malformed receipt metadata");
            String key = line.substring(0, separator);
            if (!FIELDS.contains(key) || metadata.put(key, line.substring(separator + 2)) != null) {
                throw new IllegalArgumentException("unknown or repeated receipt metadata");
            }
        }
        if (!new ArrayList<>(metadata.keySet()).equals(FIELDS)) {
            throw new IllegalArgumentException("receipt metadata is missing or not canonical");
        }
        if (!"1".equals(metadata.get("reasonnotes-receipt-format"))) {
            throw new IllegalArgumentException("unsupported receipt format");
        }
        final UUID noteId;
        final Instant importedUtc;
        try {
            noteId = UUID.fromString(metadata.get("note-id"));
            importedUtc = Instant.parse(metadata.get("imported-utc"));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid receipt identity or timestamp", error);
        }
        if (!noteId.toString().equals(metadata.get("note-id"))) {
            throw new IllegalArgumentException("receipt note UUID is not canonical");
        }
        validateInstant(importedUtc);
        if (!importedUtc.toString().equals(metadata.get("imported-utc"))) {
            throw new IllegalArgumentException("receipt timestamp is not canonical");
        }
        String receiptId = metadata.get("receipt-id");
        String revisionId = metadata.get("revision-id");
        String acknowledgingDevice = metadata.get("acknowledging-device");
        String destinationDevice = metadata.get("destination-device");
        ReceiptEnvelope receipt = new ReceiptEnvelope(
                noteId, revisionId, receiptId, acknowledgingDevice, destinationDevice, importedUtc);
        String expected = sha256(identityBytes(
                noteId, revisionId, acknowledgingDevice, destinationDevice, importedUtc));
        if (!expected.equals(receiptId)) throw new IllegalArgumentException("receipt SHA-256 mismatch");
        if (!Arrays.equals(receipt.serialize(), data)) {
            throw new IllegalArgumentException("receipt is not canonical");
        }
        return receipt;
    }

    public byte[] serialize() {
        return canonicalBytes(
                receiptId, noteId, revisionId, acknowledgingDevice, destinationDevice, importedUtc);
    }

    public String transportFilename() {
        return receiptId + ".receipt.md";
    }

    private static byte[] identityBytes(
            UUID noteId,
            String revisionId,
            String acknowledgingDevice,
            String destinationDevice,
            Instant importedUtc) {
        return ("reasonnotes-receipt-format: 1\n"
                + "note-id: " + noteId + "\n"
                + "revision-id: " + revisionId + "\n"
                + "acknowledging-device: " + acknowledgingDevice + "\n"
                + "destination-device: " + destinationDevice + "\n"
                + "imported-utc: " + importedUtc + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] canonicalBytes(
            String receiptId,
            UUID noteId,
            String revisionId,
            String acknowledgingDevice,
            String destinationDevice,
            Instant importedUtc) {
        return ("---\n"
                + "reasonnotes-receipt-format: 1\n"
                + "receipt-id: " + receiptId + "\n"
                + "note-id: " + noteId + "\n"
                + "revision-id: " + revisionId + "\n"
                + "acknowledging-device: " + acknowledgingDevice + "\n"
                + "destination-device: " + destinationDevice + "\n"
                + "imported-utc: " + importedUtc + "\n"
                + "---\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void validateHash(String value, String label) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 value");
        }
    }

    private static void validateDevice(String value, String label) {
        if (value == null || !DEVICE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    private static void validateInstant(Instant value) {
        if (value == null || value.getNano() != 0) {
            throw new IllegalArgumentException("receipt time must use whole UTC seconds");
        }
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
}
