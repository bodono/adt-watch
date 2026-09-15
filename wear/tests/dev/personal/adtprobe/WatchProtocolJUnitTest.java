package dev.personal.adtprobe;

import org.junit.Test;

/** Runs the same protocol rejection/lifetime suite through Gradle's JUnit 4 runner. */
public final class WatchProtocolJUnitTest {
    @Test public void validatesMessagesSourcesDeadlinesAndCancellation() {
        WatchProtocolTest.main(new String[0]);
    }
}
