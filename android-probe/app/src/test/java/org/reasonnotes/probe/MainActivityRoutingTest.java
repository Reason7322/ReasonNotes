package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.Test;

public final class MainActivityRoutingTest {
    private static final String LINUX_DEVICE_ID = "254997f28f11499d80928fe8a109749e";
    private static final String POCO_DEVICE_ID = "52548cf9710c43cab2fa73ed4671aea9";
    private static final UUID PROBE_NOTE = UUID.fromString("52548cf9-710c-43ca-b2fa-73ed4671aea9");

    @Test
    public void outgoingTransfersTargetLinuxAndNeverThePocoItself() throws Exception {
        assertEquals(LINUX_DEVICE_ID, privateStaticField("KDE_DEVICE_ID"));
        assertNotEquals(POCO_DEVICE_ID, privateStaticField("KDE_DEVICE_ID"));
    }

    @Test
    public void deterministicProbeNoteIdentityRemainsUnchanged() throws Exception {
        assertEquals(PROBE_NOTE, privateStaticField("PROBE_NOTE"));
    }

    private static Object privateStaticField(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
