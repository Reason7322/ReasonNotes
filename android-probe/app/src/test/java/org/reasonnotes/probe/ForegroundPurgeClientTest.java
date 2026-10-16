package org.reasonnotes.probe;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class ForegroundPurgeClientTest {
    private static final UUID NOTE = UUID.fromString(
            "11111111-1111-4111-8111-111111111111");

    @Test public void sameCanonicalHmacAsFedoraPythonProtocol() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        byte[] body = ForegroundPurgeClient.encode(Set.of(NOTE));
        assertEquals("11111111-1111-4111-8111-111111111111\n",
                new String(body, StandardCharsets.US_ASCII));
        assertEquals(
                "a6c7ba5dd6906320f9f899607f89fe11d7764250a34860bd263745125a7c5b63",
                ForegroundPurgeClient.signature(
                        key, "POST", "/v1/purges",
                        "1792160000", "0123456789abcdef0123456789abcdef", body));
        assertNotEquals(
                ForegroundPurgeClient.signature(key, "RESPONSE", "/v1/purges",
                        "1792160000", "0123456789abcdef0123456789abcdef", body),
                ForegroundPurgeClient.signature(key, "RESPONSE", "/v1/purges",
                        "1792160000", "0123456789abcdef0123456789abcdef",
                        ForegroundPurgeClient.encode(Set.of())));
    }

    @Test public void canonicalIdentifiersAndPrivateNetworkOnly() {
        assertTrue(ForegroundPurgeClient.validLanIp("192.168.100.2"));
        assertTrue(ForegroundPurgeClient.validLanIp("10.0.0.2"));
        assertTrue(ForegroundPurgeClient.validLanIp("172.16.2.5"));
        assertFalse(ForegroundPurgeClient.validLanIp("8.8.8.8"));
        assertFalse(ForegroundPurgeClient.validLanIp("127.0.0.1"));
        assertFalse(ForegroundPurgeClient.validLanIp("192.168.000.2"));
        assertFalse(ForegroundPurgeClient.validLanIp("192.168.2.999"));
        assertTrue(ForegroundPurgeClient.validKey("a".repeat(64)));
        assertFalse(ForegroundPurgeClient.validKey("ZZ".repeat(32)));
        assertEquals(Set.of(NOTE),
                ForegroundPurgeClient.decode(ForegroundPurgeClient.encode(Set.of(NOTE))));
        assertThrows(IllegalArgumentException.class,
                () -> ForegroundPurgeClient.decode("NOT-A-UUID".getBytes(StandardCharsets.US_ASCII)));
    }
}
