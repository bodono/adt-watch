package dev.personal.adtprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WatchProtocolTest {
    @Test public void nodeIdsAreNonEmptySingleLineAndBounded() {
        assertTrue(WatchProtocol.validNodeId("phone-a"));
        assertFalse(WatchProtocol.validNodeId(null));
        assertFalse(WatchProtocol.validNodeId(""));
        assertFalse(WatchProtocol.validNodeId("one\ntwo"));
        assertFalse(WatchProtocol.validNodeId("one\rtwo"));
        StringBuilder longest = new StringBuilder();
        for (int i = 0; i < 256; i++) longest.append('x');
        assertTrue(WatchProtocol.validNodeId(longest.toString()));
        assertFalse(WatchProtocol.validNodeId(longest + "x"));
    }
}
