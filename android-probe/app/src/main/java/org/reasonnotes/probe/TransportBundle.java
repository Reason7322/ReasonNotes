package org.reasonnotes.probe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Deterministic one-payload carrier for unchanged revision and receipt envelopes. */
public final class TransportBundle {
    public static final String FORMAT = "reasonnotes-transport-bundle";
    public static final int VERSION = 1;
    public static final int MAX_ITEMS = 256;
    public static final int MAX_ENVELOPE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_TOTAL_ENVELOPE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    private static final Pattern IDENTITY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern NAME = Pattern.compile(
            "reasonnotes-bundle-v1-([0-9a-f]{64})(?: \\(([1-9][0-9]*)\\))?\\.json");

    public record Item(String kind, String identity, byte[] envelope) {
        public Item {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(identity, "identity");
            envelope = Objects.requireNonNull(envelope, "envelope").clone();
        }

        @Override public byte[] envelope() { return envelope.clone(); }
    }

    public record Carrier(String filename, byte[] payload, List<Item> items) {
        public Carrier {
            Objects.requireNonNull(filename, "filename");
            payload = Objects.requireNonNull(payload, "payload").clone();
            items = List.copyOf(items);
        }

        @Override public byte[] payload() { return payload.clone(); }
    }

    private record Validated(Item item, String text) {}

    private TransportBundle() {}

    public static boolean isBundleFilename(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static Carrier create(List<Item> source) {
        Objects.requireNonNull(source, "items");
        if (source.isEmpty() || source.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("bundle must contain 1 to 256 items");
        }
        ArrayList<Validated> values = new ArrayList<>();
        int total = 0;
        for (Item item : source) {
            Validated validated = validate(item);
            total = Math.addExact(total, item.envelope.length);
            if (total > MAX_TOTAL_ENVELOPE_BYTES) {
                throw new IllegalArgumentException("bundle envelopes exceed total size limit");
            }
            values.add(validated);
        }
        rejectDuplicates(values);
        values.sort(Comparator.comparing((Validated value) -> value.item.kind)
                .thenComparing(value -> value.item.identity));
        byte[] payload = canonical(values);
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("bundle payload exceeds size limit");
        }
        String filename = "reasonnotes-bundle-v1-" + sha256(payload) + ".json";
        return new Carrier(filename, payload,
                values.stream().map(Validated::item).collect(Collectors.toList()));
    }

    public static Carrier parse(byte[] data, String filename) {
        Objects.requireNonNull(data, "payload");
        Matcher match = NAME.matcher(Objects.requireNonNull(filename, "filename"));
        if (!match.matches()) throw new IllegalArgumentException("invalid bundle filename");
        if (data.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("bundle payload exceeds size limit");
        }
        String text = decode(data, "bundle payload is not valid UTF-8");
        if (!sha256(data).equals(match.group(1))) {
            throw new IllegalArgumentException("bundle payload hash differs from filename");
        }
        final JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("bundle root must be an object");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("bundle payload is not valid JSON", error);
        }
        if (!keys(root).equals(List.of("format", "items", "version"))) {
            throw new IllegalArgumentException("bundle schema is not canonical");
        }
        if (!string(root.get("format"), "format").equals(FORMAT)
                || !root.get("version").isJsonPrimitive()
                || !root.getAsJsonPrimitive("version").isNumber()
                || root.get("version").getAsInt() != VERSION
                || !root.get("items").isJsonArray()) {
            throw new IllegalArgumentException("unsupported bundle format or version");
        }
        JsonArray array = root.getAsJsonArray("items");
        if (array.isEmpty() || array.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("bundle must contain 1 to 256 items");
        }
        ArrayList<Validated> values = new ArrayList<>();
        int total = 0;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("bundle item must be an object");
            }
            JsonObject object = element.getAsJsonObject();
            if (!keys(object).equals(List.of("envelope", "id", "kind"))) {
                throw new IllegalArgumentException("bundle item schema is not canonical");
            }
            String envelope = string(object.get("envelope"), "envelope");
            String identity = string(object.get("id"), "id");
            String kind = string(object.get("kind"), "kind");
            byte[] bytes = envelope.getBytes(StandardCharsets.UTF_8);
            total = Math.addExact(total, bytes.length);
            if (total > MAX_TOTAL_ENVELOPE_BYTES) {
                throw new IllegalArgumentException("bundle envelopes exceed total size limit");
            }
            values.add(validate(new Item(kind, identity, bytes)));
        }
        rejectDuplicates(values);
        ArrayList<Validated> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.comparing((Validated value) -> value.item.kind)
                .thenComparing(value -> value.item.identity));
        if (!values.equals(ordered) || !Arrays.equals(canonical(ordered), data)) {
            throw new IllegalArgumentException("bundle payload is not canonical");
        }
        return new Carrier(filename, data,
                values.stream().map(Validated::item).collect(Collectors.toList()));
    }

    public static File write(File directory, List<Item> items) {
        Objects.requireNonNull(directory, "directory");
        Carrier carrier = create(items);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("cannot create transport outbox");
        }
        File target = new File(directory, carrier.filename());
        File temporary = null;
        try {
            if (target.isFile()
                    && Arrays.equals(Files.readAllBytes(target.toPath()), carrier.payload)) {
                return target;
            }
            temporary = File.createTempFile(".reasonnotes-bundle-", ".tmp", directory);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(carrier.payload);
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException error) {
            throw new IllegalStateException("cannot persist transport bundle", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (IOException ignored) {
                    // The durable write result is primary; failed temp cleanup is harmless.
                }
            }
        }
    }

    private static Validated validate(Item item) {
        if (!item.kind.equals("receipt") && !item.kind.equals("revision")
                && !item.kind.equals("state-v2") && !item.kind.equals("ack-v2")) {
            throw new IllegalArgumentException("unsupported bundle item kind");
        }
        if (!IDENTITY.matcher(item.identity).matches()) {
            throw new IllegalArgumentException("invalid bundle item identity");
        }
        if (item.envelope.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("bundle envelope exceeds size limit");
        }
        String text = decode(item.envelope, "bundle envelope is not valid UTF-8");
        String parsedIdentity = switch (item.kind) {
            case "revision" -> RevisionEnvelope.parse(item.envelope).revisionId();
            case "receipt" -> ReceiptEnvelope.parse(item.envelope).receiptId();
            case "state-v2" -> StateEnvelope.parse(item.envelope).changeId();
            case "ack-v2" -> StateAckEnvelope.parse(item.envelope).ackId();
            default -> throw new IllegalArgumentException("unsupported bundle item kind");
        };
        if (!parsedIdentity.equals(item.identity)) {
            throw new IllegalArgumentException("bundle item identity differs from envelope");
        }
        return new Validated(item, text);
    }

    private static void rejectDuplicates(List<Validated> values) {
        Set<String> keys = new HashSet<>();
        for (Validated value : values) {
            if (!keys.add(value.item.kind + ":" + value.item.identity)) {
                throw new IllegalArgumentException("duplicate bundle item identity");
            }
        }
    }

    private static String string(JsonElement value, String label) {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("bundle " + label + " must be a string");
        }
        return value.getAsString();
    }

    private static List<String> keys(JsonObject object) {
        return object.entrySet().stream().map(java.util.Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private static byte[] canonical(List<Validated> values) {
        StringBuilder output = new StringBuilder("{\"format\":\"")
                .append(FORMAT).append("\",\"items\":[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) output.append(',');
            Validated value = values.get(index);
            output.append("{\"envelope\":");
            quote(output, value.text);
            output.append(",\"id\":\"").append(value.item.identity)
                    .append("\",\"kind\":\"").append(value.item.kind).append("\"}");
        }
        return output.append("],\"version\":1}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void quote(StringBuilder output, String value) {
        output.append('"');
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        output.append(String.format(Locale.ROOT, "\\u%04x", codePoint));
                    } else {
                        output.appendCodePoint(codePoint);
                    }
                }
            }
        }
        output.append('"');
    }

    private static String decode(byte[] data, String message) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(message, error);
        }
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) {
                value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
