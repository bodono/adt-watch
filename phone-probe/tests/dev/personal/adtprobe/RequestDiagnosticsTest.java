package dev.personal.adtprobe;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class RequestDiagnosticsTest {
    private static final String REQUEST = "01234567-89ab-cdef-0123-456789abcdef";
    private Context context;
    private File current, previous;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        current = new File(context.getFilesDir(), RequestDiagnostics.FILE_NAME);
        previous = new File(context.getFilesDir(), RequestDiagnostics.FILE_NAME + ".1");
        Files.deleteIfExists(current.toPath()); Files.deleteIfExists(previous.toPath());
        ReflectionHelpers.setStaticField(RequestDiagnostics.class, "lastPassiveFailure", null);
        ReflectionHelpers.setStaticField(RequestDiagnostics.class, "lastPassiveElapsed", -1L);
        ReflectionHelpers.setStaticField(RequestDiagnostics.class, "skippedPassive", 0);
    }

    @Test public void requestCorrelationContainsOnlyHashAndClosedDiagnostics() throws Exception {
        RequestDiagnostics.tap(context, AlarmAction.ARM_STAY, REQUEST);
        AdtPortalClient.Result privateResult = new AdtPortalClient.Result(AdtPortalClient.Status.VERIFY_LOGIN,
            AlarmStateProtocol.State.UNKNOWN, "private-system", "private-partition", "private-home", "private-panel", 0);
        RequestDiagnostics.query(context, AlarmStateProtocol.QueryIntent.USER, AlarmAction.ARM_STAY, REQUEST, 37,
            RequestDiagnostics.QueryOutcome.FAILED, privateResult, AlarmStateProtocol.DeclineReason.SIGN_IN_REQUIRED);
        RequestDiagnostics.declined(context, AlarmAction.ARM_STAY, REQUEST, AlarmStateProtocol.DeclineReason.SIGN_IN_REQUIRED);
        RequestDiagnostics.requested(context, AlarmAction.DISARM, REQUEST);
        String log = read(current), token = RequestDiagnostics.token(REQUEST);
        assertEquals(16, token.length());
        assertEquals(4, log.split("request=" + token, -1).length - 1);
        assertTrue(log.contains("stage=TAP")); assertTrue(log.contains("stage=DECLINED"));
        assertTrue(log.contains("stage=CLICK_ATTEMPTED")); assertTrue(log.contains("durationMs=37"));
        assertTrue(log.contains("reason=SIGN_IN_REQUIRED")); assertTrue(log.contains("code=SESSION/NONE"));
        assertTrue(log.contains("Z elapsedMs="));
        assertFalse(log.contains(REQUEST)); assertFalse(log.contains("private-"));
        assertEquals("-", RequestDiagnostics.token("password\nPRIVATE"));
    }

    @Test public void arbitraryRecoveryTextCannotLeakIntoTheFile() throws Exception {
        RequestDiagnostics.recovery(context, RequestDiagnostics.RecoveryStage.RESULT, false, "password@example.com\nCOOKIE=secret", 42);
        RequestDiagnostics.recovery(context, RequestDiagnostics.RecoveryStage.RESULT, false, "VERIFY/PARTITION/HTTP/401", 2);
        RequestDiagnostics.recovery(context, RequestDiagnostics.RecoveryStage.RESULT, true, "SUBMIT/HTTP/403", 3);
        String log = read(current);
        assertFalse(log.contains("password")); assertFalse(log.contains("COOKIE"));
        assertTrue(log.contains("code=UNAVAILABLE")); assertTrue(log.contains("code=VERIFY/PARTITION/HTTP/401"));
        assertTrue(log.contains("code=SUBMIT/HTTP/403"));
    }

    @Test public void ambientReadsAreQuietAndRepeatedFailuresCannotFloodTheLog() throws Exception {
        RequestDiagnostics.query(context, AlarmStateProtocol.QueryIntent.PASSIVE, null, null, 1,
            RequestDiagnostics.QueryOutcome.READY, null, null);
        assertFalse(current.exists());
        for (int i = 0; i < 100; i++) passiveFailure();
        assertEquals(1, read(current).lines().count());
        RequestDiagnostics.declined(context, AlarmAction.ARM_STAY, REQUEST, AlarmStateProtocol.DeclineReason.SIGN_IN_REQUIRED);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1)); passiveFailure();
        String log = read(current);
        assertEquals(3, log.lines().count()); assertTrue(log.contains("suppressedPassive=99"));
        assertTrue(log.contains("stage=DECLINED"));
    }

    @Test public void rotationKeepsTwoBoundedFilesAndTheLatestFailure() throws Exception {
        for (int i = 0; i < 2400; i++) RequestDiagnostics.tap(context, AlarmAction.ARM_STAY, REQUEST);
        RequestDiagnostics.declined(context, AlarmAction.ARM_STAY, REQUEST, AlarmStateProtocol.DeclineReason.SIGN_IN_REQUIRED);
        assertTrue(previous.exists());
        assertTrue(current.length() <= RequestDiagnostics.MAX_FILE_BYTES);
        assertTrue(previous.length() <= RequestDiagnostics.MAX_FILE_BYTES);
        assertTrue(read(current).endsWith("reason=SIGN_IN_REQUIRED\n"));
        assertTrue(read(previous).endsWith("\n"));
    }

    @Test public void unwritableDiagnosticPathDoesNotInterruptCaller() throws Exception {
        assertTrue(current.mkdir());
        RequestDiagnostics.tap(context, AlarmAction.ARM_STAY, REQUEST);
        RequestDiagnostics.declined(context, AlarmAction.ARM_STAY, REQUEST, AlarmStateProtocol.DeclineReason.STATUS_CHECK_FAILED);
        assertTrue(current.isDirectory()); assertTrue(current.delete());
    }

    private void passiveFailure() {
        RequestDiagnostics.query(context, AlarmStateProtocol.QueryIntent.PASSIVE, null, null, 1,
            RequestDiagnostics.QueryOutcome.FAILED, null, AlarmStateProtocol.DeclineReason.SIGN_IN_REQUIRED);
    }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
}
