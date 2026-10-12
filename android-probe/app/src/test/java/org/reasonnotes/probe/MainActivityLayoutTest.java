package org.reasonnotes.probe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MainActivityLayoutTest {
    @Test
    public void contentStartsBelowSystemBarsAndActionBar() {
        assertEquals(314, MainActivity.contentTopPadding(24, 122, 168));
    }
}
