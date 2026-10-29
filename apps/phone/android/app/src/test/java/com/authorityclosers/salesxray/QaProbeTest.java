package com.authorityclosers.salesxray;

import org.junit.Test;
import static org.junit.Assert.*;

public class QaProbeTest {
    @Test public void readOnlyScreenPaths() {
        for (String path : new String[]{"/dashboard/", "/leads/", "/analysis/calls/test-id/", "/chats/", "/organisation/", "/settings/"})
            assertTrue(path, QaProbe.allowed(path));
    }
    @Test public void rejectOtherOriginsAndTraversal() {
        for (String path : new String[]{"//evil.test", "/settings/../auth", "/settings/%2e%2e/auth", "/settings/\\evil", "/auth", "/v1", "/settings-evil"})
            assertFalse(path, QaProbe.allowed(path));
        assertFalse(QaProbe.allowed(null));
    }
}
