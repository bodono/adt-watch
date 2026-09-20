package dev.personal.adtprobe;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

/** Inert login/read fixtures: no real account, network, widget or alarm command. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AdtSessionRecoveryTest {
    private Context app;
    private AdtSessionRecovery.Sessions oldSessions;
    private AdtSessionRecovery.Login oldLogin;
    private AdtSessionRecovery.Read oldRead;
    private AdtSessionRecovery.Credentials oldCredentials;
    private String version;
    private int logins, reads, cookieWrites;
    private AdtLoginClient.Status loginStatus;
    private AdtLoginClient.Stage loginStage = AdtLoginClient.Stage.SUBMIT;
    private AdtPortalClient.Result response;
    private char[] suppliedPassword;
    private long website, loginStarted, loginDeadline;
    private PhoneAlarmState.Schedule oldSchedule;
    private PhoneAlarmState.Query oldQuery;
    private PhoneAlarmState.Publish oldPublish;
    private PhoneAlarmState.Schedule oldKeepAlive;
    private int published;
    private final List<Runnable> scheduled = new ArrayList<>();
    private int phoneReads;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).edit().clear().commit();
        app.getSharedPreferences("adt_portal_binding", 0).edit().clear().commit();
        assertTrue(AdtPortalSession.bind(app, "system-1", "partition-1"));
        ReflectionHelpers.setStaticField(AdtSessionRecovery.class, "websiteOwner", 0L);
        ReflectionHelpers.setStaticField(AdtSessionRecovery.class, "queuedElapsed", -1L);
        app.getSharedPreferences(PhoneAlarmState.PREFERENCES, 0).edit().clear().commit();
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 3);
        ReflectionHelpers.setStaticField(PhoneAlarmState.class, "storageFailed", false);
        oldSchedule = PhoneAlarmState.scheduleOperation; oldQuery = PhoneAlarmState.queryOperation;
        oldPublish = PhoneAlarmState.publishOperation; PhoneAlarmState.publishOperation = context -> published++;
        oldKeepAlive = PhoneAlarmState.keepAliveOperation; PhoneAlarmState.keepAliveOperation = (action, delay) -> { };
        PhoneAlarmState.scheduleOperation = (action, delay) -> scheduled.add(action);
        PhoneAlarmState.queryOperation = (context, deadline) -> { phoneReads++; return response; };
        oldSessions = AdtSessionRecovery.sessions; oldLogin = AdtSessionRecovery.loginOperation;
        oldRead = AdtSessionRecovery.readOperation; oldCredentials = AdtSessionRecovery.credentials;
        version = UUID.randomUUID().toString(); loginStatus = AdtLoginClient.Status.SUBMITTED;
        response = ready("system-1");
        AdtSessionRecovery.credentials = new AdtSessionRecovery.Credentials() {
            @Override public String version(Context context) { return version; }
            @Override public AdtCredentialStore.Credentials load(Context context) {
                suppliedPassword = "inert-password".toCharArray();
                return new AdtCredentialStore.Credentials("inert-user", suppliedPassword, version);
            }
        };
        AdtSessionRecovery.sessions = (context, deadline) -> new AdtPortalClient.Session() {
            @Override public String cookies() { return "fixture=value"; }
            @Override public String userAgent() { return "fixture-agent"; }
            @Override public void storeCookie(String value) { cookieWrites++; }
        };
        AdtSessionRecovery.loginOperation = (session, username, password, deadline) -> {
            logins++; loginStarted = SystemClock.elapsedRealtime(); loginDeadline = deadline;
            session.storeCookie("inert=new"); return loginAnswer();
        };
        AdtSessionRecovery.readOperation = (session, binding, deadline) -> { reads++; return response; };
    }
    @After public void restore() {
        PhoneAlarmState.scheduleOperation = oldSchedule; PhoneAlarmState.queryOperation = oldQuery;
        PhoneAlarmState.publishOperation = oldPublish; PhoneAlarmState.keepAliveOperation = oldKeepAlive;
        AdtSessionRecovery.endInteractiveSignIn(website);
        AdtSessionRecovery.sessions = oldSessions; AdtSessionRecovery.loginOperation = oldLogin;
        AdtSessionRecovery.readOperation = oldRead; AdtSessionRecovery.credentials = oldCredentials;
    }
    private AdtLoginClient.Result loginAnswer() {
        return new AdtLoginClient.Result(loginStatus, 1, loginStage, AdtLoginClient.Reason.NONE, 200);
    }
    private AdtPortalClient.Result ready(String system) {
        return new AdtPortalClient.Result(AdtPortalClient.Status.READY, AlarmStateProtocol.State.DISARMED,
            system, "partition-1", "", "", 1);
    }
    private AdtPortalClient.Result failure(AdtPortalClient.Status status) {
        return new AdtPortalClient.Result(status, AlarmStateProtocol.State.UNKNOWN, "", "", "", "", 1);
    }
    private long deadline() { return SystemClock.elapsedRealtime() + 20_000; }
    /** The production sequence: the read returns its failure, the queued task runs later on the reads worker. */
    private AdtPortalClient.Result recover(AdtPortalClient.Result result) {
        if (!AdtSessionRecovery.recoverLater(app, AdtPortalSession.binding(app), result)) return result;
        assertEquals(1, scheduled.size());
        scheduled.remove(0).run();
        return PhoneAlarmState.snapshot(app).availability == AlarmStateProtocol.Availability.READY ? response : result;
    }
    private void allowNextAttempt() {
        app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).edit().putLong("attemptWall", 1).commit();
    }

    @Test public void savedCredentialsRequireExplicitSuccessfulTestBeforeBackgroundUse() {
        AdtPortalClient.Result failure = failure(AdtPortalClient.Status.LOGIN_REQUIRED);
        assertSame(failure, recover(failure)); assertEquals(0, logins);
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready);
        assertEquals(1, logins); assertEquals(1, reads);
        assertArrayEquals(new char[suppliedPassword.length], suppliedPassword);
        allowNextAttempt();
        assertSame(response, recover(failure)); assertEquals(2, logins); assertEquals(2, reads);
        assertEquals("https://www.alarm.com", AdtPortalSession.verifiedOrigin(app));
    }
    @Test public void ordinaryNetworkFailureOrSuccessfulReadNeverSubmitsCredentials() {
        AdtPortalClient.Result unavailable = failure(AdtPortalClient.Status.UNAVAILABLE);
        assertSame(unavailable, recover(unavailable)); assertSame(response, recover(response)); assertEquals(0, logins);
    }
    @Test public void wrongHomeDoesNotEnableOrReturnAState() {
        response = ready("other-system");
        assertFalse(AdtSessionRecovery.test(app, deadline()).ready);
        allowNextAttempt();
        AdtPortalClient.Result original = failure(AdtPortalClient.Status.LOGIN_REQUIRED);
        assertSame(original, recover(original)); assertEquals(1, logins);
        assertFalse(app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getBoolean("enabled", false));
    }
    @Test public void submittedLoginWithVerificationRequiredCannotEnableRecovery() {
        response = failure(AdtPortalClient.Status.VERIFY_LOGIN);
        assertFalse(AdtSessionRecovery.test(app, deadline()).ready);
        assertEquals(1, logins); assertEquals(1, reads);
        assertFalse(app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getBoolean("enabled", false));
        assertTrue(app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getBoolean("blocked", false));
        allowNextAttempt(); recover(failure(AdtPortalClient.Status.LOGIN_REQUIRED)); assertEquals(1, logins);
    }
    @Test public void rejectedPasswordPausesRatherThanRetryingAtEveryWatchRefresh() {
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); allowNextAttempt();
        loginStatus = AdtLoginClient.Status.REJECTED;
        AdtPortalClient.Result original = failure(AdtPortalClient.Status.LOGIN_REQUIRED);
        assertSame(original, recover(original)); assertEquals(2, logins);
        allowNextAttempt(); assertSame(original, recover(original)); assertEquals(2, logins);
        loginStatus = AdtLoginClient.Status.SUBMITTED;
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); assertEquals(3, logins);
    }
    @Test public void anUnusableLoginPageIsRetriedLaterButAJudgedSubmissionPauses() {
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); allowNextAttempt();
        AdtPortalClient.Result original = failure(AdtPortalClient.Status.LOGIN_REQUIRED);
        loginStatus = AdtLoginClient.Status.UNSUPPORTED; loginStage = AdtLoginClient.Stage.FORM;
        assertSame(original, recover(original)); assertEquals(2, logins);
        assertFalse("No credentials were submitted, so only the retry delay applies",
            app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getBoolean("blocked", false));
        assertEquals("FORM/NONE/200", app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getString("code", ""));
        assertSame(original, recover(original)); assertEquals("Cooling down", 2, logins);
        allowNextAttempt(); assertSame(original, recover(original)); assertEquals(3, logins);
        loginStage = AdtLoginClient.Stage.SUBMIT; // An unrecognised answer after the POST left the phone.
        allowNextAttempt(); assertSame(original, recover(original)); assertEquals(4, logins);
        assertTrue("The credentials were judged: paused", app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getBoolean("blocked", false));
        allowNextAttempt(); assertSame(original, recover(original)); assertEquals(4, logins);
        loginStatus = AdtLoginClient.Status.VERIFY_LOGIN; loginStage = AdtLoginClient.Stage.FORM;
        assertFalse(AdtSessionRecovery.test(app, deadline()).ready);
        assertTrue("A challenge on the login page needs the owner", app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getBoolean("blocked", false));
    }
    @Test public void transientLoginFailureIsRateLimitedAcrossRepeatedCalls() {
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); allowNextAttempt();
        loginStatus = AdtLoginClient.Status.UNAVAILABLE;
        AdtPortalClient.Result original = failure(AdtPortalClient.Status.LOGIN_REQUIRED);
        recover(original); recover(original); assertEquals(2, logins);
        allowNextAttempt(); recover(original); assertEquals(3, logins);
    }
    @Test public void aFailedReadReturnsAtOnceAndRecoveryRunsLaterWithItsOwnBudget() {
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); allowNextAttempt();
        AdtPortalClient.Result original = failure(AdtPortalClient.Status.LOGIN_REQUIRED);
        assertTrue(AdtSessionRecovery.recoverLater(app, AdtPortalSession.binding(app), original));
        assertEquals("The read that noticed did not log in", 1, logins);
        assertEquals(1, scheduled.size());
        assertFalse("One queued task at a time", AdtSessionRecovery.recoverLater(app, AdtPortalSession.binding(app), original));
        assertEquals(1, scheduled.size());
        ReentrantLock queryLock = ReflectionHelpers.getStaticField(PhoneAlarmState.class, "QUERY_LOCK");
        AdtSessionRecovery.readOperation = (session, binding, deadline) -> {
            reads++; assertTrue("Recovery runs under the query lock", queryLock.isHeldByCurrentThread()); return response;
        };
        scheduled.remove(0).run();
        assertEquals(2, logins); assertEquals(2, reads);
        assertTrue("The login had the background budget, not the read's leftover deadline",
            loginDeadline - loginStarted >= AdtSessionRecovery.BACKGROUND_BUDGET_MS);
        assertEquals("One ordinary read stored the recovered state", 1, phoneReads);
        assertEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(app).availability);
        assertEquals("...and the watch was told", 1, published);
        assertFalse(queryLock.isLocked());
        assertTrue("The next expiry can queue again", scheduled.isEmpty());
    }
    @Test public void aFailedRecoveryStoresNothing() {
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); allowNextAttempt();
        loginStatus = AdtLoginClient.Status.UNAVAILABLE;
        AdtPortalClient.Result original = failure(AdtPortalClient.Status.LOGIN_REQUIRED);
        assertSame(original, recover(original));
        assertEquals(2, logins); assertEquals(0, phoneReads); assertEquals(0, published);
        assertNotEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(app).availability);
    }
    @Test public void aSuccessfulRecoveryTellsTheWatchEvenIfItsFollowUpReadFails() {
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); allowNextAttempt();
        PhoneAlarmState.queryOperation = (context, deadline) -> { phoneReads++; return failure(AdtPortalClient.Status.UNAVAILABLE); };
        AdtPortalClient.Result original = failure(AdtPortalClient.Status.LOGIN_REQUIRED);
        assertTrue(AdtSessionRecovery.recoverLater(app, AdtPortalSession.binding(app), original));
        scheduled.remove(0).run();
        assertEquals(2, logins); assertEquals(1, phoneReads);
        assertNotEquals(AlarmStateProtocol.Availability.READY, PhoneAlarmState.snapshot(app).availability);
        assertEquals("The watch is told to query again; that reply is read with the recovered session", 1, published);
    }
    @Test public void nothingIsQueuedForNetworkFailuresOrWhilePausedCoolingDownOrSigningIn() {
        AdtPortalSession.Binding binding = AdtPortalSession.binding(app);
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready);
        assertFalse(AdtSessionRecovery.recoverLater(app, binding, failure(AdtPortalClient.Status.UNAVAILABLE)));
        assertFalse(AdtSessionRecovery.recoverLater(app, binding, response));
        assertFalse("Cooling down after the test", AdtSessionRecovery.recoverLater(app, binding, failure(AdtPortalClient.Status.LOGIN_REQUIRED)));
        allowNextAttempt(); website = AdtSessionRecovery.beginInteractiveSignIn();
        assertFalse("Sign-in page open", AdtSessionRecovery.recoverLater(app, binding, failure(AdtPortalClient.Status.LOGIN_REQUIRED)));
        AdtSessionRecovery.endInteractiveSignIn(website); website = 0;
        app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).edit().putBoolean("blocked", true).commit();
        assertFalse("Paused", AdtSessionRecovery.recoverLater(app, binding, failure(AdtPortalClient.Status.LOGIN_REQUIRED)));
        assertTrue(scheduled.isEmpty()); assertEquals(1, logins);
    }
    @Test public void changedCredentialsRequireAnotherExplicitTest() {
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); allowNextAttempt();
        version = UUID.randomUUID().toString();
        recover(failure(AdtPortalClient.Status.LOGIN_REQUIRED)); assertEquals(1, logins);
    }
    @Test public void interactiveWebsitePreventsAutoLoginAndInvalidatesOldCookieWrites() {
        website = AdtSessionRecovery.beginInteractiveSignIn();
        assertFalse(AdtSessionRecovery.test(app, deadline()).ready); assertEquals(0, logins);
        AdtSessionRecovery.endInteractiveSignIn(website);
        AdtSessionRecovery.loginOperation = (session, username, password, deadline) -> {
            logins++; website = AdtSessionRecovery.beginInteractiveSignIn();
            session.storeCookie("must-not-be-stored"); return loginAnswer();
        };
        assertFalse(AdtSessionRecovery.test(app, deadline()).ready);
        assertEquals(0, cookieWrites); assertEquals(0, reads);
    }
    @Test public void cancelledTestCannotWriteCookiesOrEnableRecovery() {
        AdtSessionRecovery.loginOperation = (session, username, password, deadline) -> {
            logins++; AdtSessionRecovery.cancelTest(); session.storeCookie("must-not-be-stored"); return loginAnswer();
        };
        assertFalse(AdtSessionRecovery.test(app, deadline()).ready);
        assertEquals(0, cookieWrites); assertEquals(0, reads);
    }
    @Test public void cancelledRetestDoesNotReenablePreviouslyRejectedCredentials() {
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); allowNextAttempt();
        loginStatus = AdtLoginClient.Status.REJECTED;
        recover(failure(AdtPortalClient.Status.LOGIN_REQUIRED)); assertEquals(2, logins);
        AdtSessionRecovery.loginOperation = (session, username, password, deadline) -> {
            logins++; AdtSessionRecovery.cancelTest(); session.storeCookie("cancelled"); return loginAnswer();
        };
        assertFalse(AdtSessionRecovery.test(app, deadline()).ready);
        allowNextAttempt(); recover(failure(AdtPortalClient.Status.LOGIN_REQUIRED)); assertEquals(3, logins);
    }
    @Test public void olderStatusSessionCannotRotateCookiesAfterLoginStarts() {
        AdtPortalClient.Session old = AdtSessionRecovery.guardSession(new AdtPortalClient.Session() {
            @Override public String cookies() { return "old=value"; }
            @Override public String userAgent() { return "fixture"; }
            @Override public void storeCookie(String cookie) { fail("Older session wrote a cookie"); }
        });
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready);
        try { old.storeCookie("old=value"); fail("Old response accepted"); }
        catch (AdtPortalClient.SessionUnavailable expected) { /* A transient failure for the status client, not an unsupported response. */ }
    }
    @Test public void normalStatusSessionCreatedDuringLoginCannotWriteCookies() {
        AdtSessionRecovery.loginOperation = (session, username, password, deadline) -> {
            logins++;
            AdtPortalClient.Session competing = AdtSessionRecovery.guardSession(new AdtPortalClient.Session() {
                @Override public String cookies() { return "old=value"; }
                @Override public String userAgent() { return "fixture"; }
                @Override public void storeCookie(String cookie) { fail("Competing read wrote cookies during login"); }
            });
            session.storeCookie("new=value");
            try { competing.storeCookie("old=value"); fail("Concurrent cookie write accepted"); }
            catch (AdtPortalClient.SessionUnavailable expected) { }
            try { competing.cookies(); fail("Concurrent read accepted"); }
            catch (AdtPortalClient.SessionUnavailable expected) { }
            return loginAnswer();
        };
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); assertEquals(1, cookieWrites);
    }
    @Test public void interruptedWorkerDoesNotStartCredentialLogin() {
        Thread.currentThread().interrupt();
        try { assertFalse(AdtSessionRecovery.test(app, deadline()).ready); assertEquals(0, logins); }
        finally { Thread.interrupted(); }
    }
    @Test public void expiryAndBindingChangesRejectOtherwiseSuccessfulLogin() {
        AdtSessionRecovery.readOperation = (session, binding, deadline) -> {
            reads++; AdtPortalSession.bind(app, "other-home", "other-partition"); return response;
        };
        assertFalse(AdtSessionRecovery.test(app, deadline()).ready);
        assertFalse(app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getBoolean("enabled", false));
    }
    @Test public void cookieCommitPastDeadlineDoesNotEnableBackgroundRecovery() {
        AdtSessionRecovery.sessions = (context, deadline) -> new AdtLoginSession(new AdtPortalClient.Session() {
            @Override public String cookies() { return "twoFactorAuthenticationId=inert-trust"; }
            @Override public String userAgent() { return "fixture"; }
            @Override public void storeCookie(String cookie) { }
            @Override public void persist() { ShadowSystemClock.advanceBy(Duration.ofSeconds(21)); }
        });
        assertFalse(AdtSessionRecovery.test(app, deadline()).ready);
        assertFalse(app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getBoolean("enabled", false));
    }
    @Test public void thePhoneCanSayWhyAutomaticLoginIsNotHelping() {
        version = ""; assertNull("Nothing saved, nothing to say", AdtSessionRecovery.describe(app));
        version = UUID.randomUUID().toString();
        assertTrue(AdtSessionRecovery.describe(app).contains("not verified"));
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready);
        assertEquals("Automatic login is enabled.", AdtSessionRecovery.describe(app));
        allowNextAttempt(); loginStatus = AdtLoginClient.Status.REJECTED;
        recover(failure(AdtPortalClient.Status.LOGIN_REQUIRED));
        String paused = AdtSessionRecovery.describe(app);
        assertTrue(paused, paused.contains("paused after SUBMIT/NONE/200"));
        website = AdtSessionRecovery.beginInteractiveSignIn();
        assertTrue(AdtSessionRecovery.describe(app).contains("sign-in page is closed"));
        AdtSessionRecovery.endInteractiveSignIn(website); website = 0;
        AdtSessionRecovery.interactiveSignInCompleted(app);
        assertEquals("Automatic login is enabled.", AdtSessionRecovery.describe(app));
        loginStatus = AdtLoginClient.Status.UNAVAILABLE;
        recover(failure(AdtPortalClient.Status.LOGIN_REQUIRED));
        assertEquals("The website sign-in also cleared the retry delay", 3, logins);
        String waiting = AdtSessionRecovery.describe(app);
        assertTrue(waiting, waiting.contains("last ended with SUBMIT/NONE/200; it retries in about 5 minutes"));
        assertFalse(app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getBoolean("blocked", false));
    }
    @Test public void completingTheWebsiteSignInResumesPausedAttemptsWithoutReVerifying() {
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready); allowNextAttempt();
        loginStatus = AdtLoginClient.Status.REJECTED;
        AdtPortalClient.Result original = failure(AdtPortalClient.Status.LOGIN_REQUIRED);
        assertSame(original, recover(original)); assertEquals(2, logins);
        allowNextAttempt(); assertSame(original, recover(original)); assertEquals("Paused", 2, logins);
        AdtSessionRecovery.interactiveSignInCompleted(app);
        loginStatus = AdtLoginClient.Status.SUBMITTED;
        assertSame("Resumed without a retest and without waiting out the delay", response, recover(original));
        assertEquals(3, logins);
        assertTrue(app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getBoolean("enabled", false));
        app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).edit().clear().commit();
        AdtSessionRecovery.interactiveSignInCompleted(app);
        allowNextAttempt(); assertSame("Never-verified credentials stay disabled", original, recover(original));
        assertEquals(3, logins);
    }
    @Test public void diagnosticsContainOnlyClosedCodesNotCredentialsOrHomeIdentifiers() {
        assertTrue(AdtSessionRecovery.test(app, deadline()).ready);
        String values = app.getSharedPreferences(AdtSessionRecovery.PREFERENCES, 0).getAll().toString();
        assertFalse(values.contains("inert-user")); assertFalse(values.contains("inert-password"));
        assertFalse(values.contains("system-1")); assertFalse(values.contains("partition-1"));
    }
}
