package org.reasonnotes.probe;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Foreground-only phone client; talks to the paired Fedora app over the LAN.
 * Never transports note bodies, editor drafts or revision history. */
public final class ForegroundPurgeClient {
    public static final int PORT = 49233;
    public static final String PREFS = "reasonnotes-purge-link";
    private static final int MAX_BYTES = 262144;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String HOST = "host";
    private static final String KEY = "key";

    public record Settings(String host, String key) {}
    public record Result(Set<UUID> incoming, String status) {}

    private ForegroundPurgeClient() {}

    public static boolean validLanIp(String value) {
        if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = value.split("\\.");
        int[] ip = new int[4];
        for (int i = 0; i < 4; i++) {
            ip[i] = Integer.parseInt(parts[i]);
            if (ip[i] > 255 || !parts[i].equals(Integer.toString(ip[i]))) return false;
        }
        return ip[0] == 10
                || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)
                || (ip[0] == 192 && ip[1] == 168);
    }

    public static boolean validKey(String key) {
        return key != null && key.matches("[0-9a-f]{64}");
    }

    public static void configure(Context context, String host, String key) {
        if (!validLanIp(host)) throw new IllegalArgumentException("Use your computer's local IPv4 address");
        if (!validKey(key)) throw new IllegalArgumentException("Pairing key must be 64 lowercase hexadecimal characters");
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(HOST, host).putString(KEY, key).commit()) {
            throw new IllegalStateException("Could not save pairing settings");
        }
    }

    public static Settings settings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Settings(prefs.getString(HOST, ""), prefs.getString(KEY, ""));
    }

    public static byte[] encode(Set<UUID> ids) {
        StringBuilder value = new StringBuilder();
        for (UUID id : new TreeSet<>(ids)) value.append(id).append('\n');
        return value.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public static Set<UUID> decode(byte[] payload) {
        if (payload.length > MAX_BYTES) throw new IllegalArgumentException("Too many deletion IDs");
        String value = new String(payload, StandardCharsets.US_ASCII);
        TreeSet<UUID> ids = new TreeSet<>();
        for (String line : value.split("\n")) {
            if (line.isEmpty()) continue;
            UUID id = UUID.fromString(line);
            if (!line.equals(id.toString())) throw new IllegalArgumentException("Noncanonical deletion ID");
            ids.add(id);
        }
        return Set.copyOf(ids);
    }

    public static String signature(
            byte[] key, String method, String path, String epoch, String nonce, byte[] body) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String message = String.join("\n", method, path, epoch, nonce, digest);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot sign deletion sync request", error);
        }
    }

    public static Result poll(String ip, String key, Set<UUID> pending) throws IOException {
        if (!validLanIp(ip) || !validKey(key)) return new Result(Set.of(), "Not paired");
        byte[] body = encode(pending);
        if (body.length > MAX_BYTES) throw new IOException("Too many deletion IDs");
        byte[] keyBytes = HexFormat.of().parseHex(key);
        String epoch = Long.toString(Instant.now().getEpochSecond());
        byte[] nonceBytes = new byte[16];
        RANDOM.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        String path = "/v1/purges";
        String auth = signature(keyBytes, "POST", path, epoch, nonce, body);

        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://" + ip + ":" + PORT + path).openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(2000);
        connection.setUseCaches(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "text/plain; charset=ascii");
        connection.setRequestProperty("X-RN-Time", epoch);
        connection.setRequestProperty("X-RN-Nonce", nonce);
        connection.setRequestProperty("X-RN-Sig", auth);
        try {
            connection.getOutputStream().write(body);
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Paired computer rejected request: " + status);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (output.size() + n > MAX_BYTES) throw new IOException("Sync response too large");
                    output.write(buffer, 0, n);
                }
            }
            byte[] response = output.toByteArray();
            String responseSignature = connection.getHeaderField("X-RN-Response-Sig");
            String expected = signature(keyBytes, "RESPONSE", path, epoch, nonce, response);
            if (responseSignature == null ||
                    !MessageDigest.isEqual(
                            responseSignature.getBytes(StandardCharsets.US_ASCII),
                            expected.getBytes(StandardCharsets.US_ASCII))) {
                throw new IOException("Computer deletion response failed authentication");
            }
            return new Result(decode(response), "Connected");
        } finally {
            connection.disconnect();
        }
    }

    public static Set<UUID> synchronize(ProbeRepository repository, Settings settings)
            throws IOException {
        Result result = poll(settings.host(), settings.key(), repository.purgedNoteIds());
        Set<UUID> before = repository.purgedNoteIds();
        Set<UUID> added = new TreeSet<>(result.incoming());
        added.removeAll(before);
        if (!added.isEmpty()) repository.applyRemotePurges(added);
        return Set.copyOf(added);
    }
}
