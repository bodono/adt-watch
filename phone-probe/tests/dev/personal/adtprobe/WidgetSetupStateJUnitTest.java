package dev.personal.adtprobe;

import org.junit.Test;

/** Gradle entry point; the underlying policy suite also runs without JUnit or Android. */
public final class WidgetSetupStateJUnitTest {
    @Test public void setupStatePolicy() {
        WidgetSetupStateTest.main(new String[0]);
    }
}
