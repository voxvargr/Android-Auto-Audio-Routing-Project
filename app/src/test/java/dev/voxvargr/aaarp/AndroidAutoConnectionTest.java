package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class AndroidAutoConnectionTest {
    @Test
    public void normalizedLabelAliasIgnoresCaseAndSeparators() {
        assertEquals(
                "carlinktest1234",
                AndroidAutoConnection.normalizeLabel(" CARLINK_TEST-12:34 ")
        );
    }

    @Test
    public void sameLabelSharesAliasAcrossExactConnectionKeys() {
        AndroidAutoConnection first = new AndroidAutoConnection(
                "wifi_first",
                "CARLINK_TEST1234",
                true
        );
        AndroidAutoConnection second = new AndroidAutoConnection(
                "wifi_second",
                "carlink-test1234",
                true
        );

        assertNotEquals(first.key(), second.key());
        assertEquals(first.normalizedLabelAlias(), second.normalizedLabelAlias());
    }

    @Test
    public void fallbackConnectionHasNoLabelAlias() {
        assertEquals("", AndroidAutoConnection.fallback().normalizedLabelAlias());
    }
}
