package dev.personal.adtprobe;

import org.junit.Test;

/** Runs the plain-Java protocol checks in both Gradle application test suites. */
public final class ArmExperimentProtocolJUnitTest {
    @Test public void closedOneShotProtocol() { ArmExperimentProtocolTest.main(new String[0]); }
}
