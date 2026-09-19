package dev.personal.adtprobe;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** The fake query has no network or alarm path. WebView rendering uses Robolectric only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AdtPortalSetupActivityTest {
    private Context context;
    private ActivityController<AdtPortalSetupActivity> controller;
    private AdtPortalSetupActivity activity;
    private final Queue<Runnable> work = new ArrayDeque<>();
    private Executor oldExecutor;
    private AdtPortalSetupActivity.QueryOperation oldOperation;
    private AdtPortalClient.Result answer;
    private AdtPortalClient.Session queriedSession;
    private String queriedCookies;
    private int queries;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        bindingPreferences().edit().clear().commit();
        context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).edit().clear()
            .putString("sentinel", "unchanged").commit();
        oldExecutor = AdtPortalSetupActivity.queryExecutor; oldOperation = AdtPortalSetupActivity.queryOperation;
        AdtPortalSetupActivity.queryExecutor = work::add;
        AdtPortalSetupActivity.queryOperation = (session, deadline) -> {
            queries++; queriedSession = session; queriedCookies = session.cookies(); return answer;
        };
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        controller = Robolectric.buildActivity(AdtPortalSetupActivity.class).setup().visible();
        activity = controller.get(); controller.windowFocusChanged(true); idle();
    }

    @After public void close() {
        if (controller != null) controller.pause().stop().destroy();
        AdtPortalSetupActivity.queryExecutor = oldExecutor; AdtPortalSetupActivity.queryOperation = oldOperation;
    }

    @Test public void openingLoginAndQueryingNeverBindOrChangeAlarmState() {
        Map<String, ?> alarm = new HashMap<>(context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).getAll());
        assertEquals(0, queries); assertNull(AdtPortalSession.binding(context));
        assertEquals(AdtPortalSetupActivity.LOGIN_URL, Shadows.shadowOf(web()).getLastLoadedUrl());
        answer = ready(); begin();
        assertFalse(button("Check live status").isEnabled());
        assertNull(AdtPortalSession.binding(context)); finishQuery();
        assertEquals(1, queries); assertNull(AdtPortalSession.binding(context));
        assertTrue(button("Use this ADT system").isEnabled());
        String text = screenText();
        assertTrue(text.contains("Disarmed")); assertTrue(text.contains("Inert home"));
        assertTrue(text.contains("Inert partition")); assertTrue(text.contains("0.3 seconds"));
        assertEquals(alarm, context.getSharedPreferences(PhoneAlarmState.PREFERENCES, Context.MODE_PRIVATE).getAll());
        Map<String, ?> diagnostic = context.getSharedPreferences("adt_portal_diagnostics", Context.MODE_PRIVATE).getAll();
        assertEquals(Set.of("code", "status", "elapsedMillis"), diagnostic.keySet());
        assertFalse(diagnostic.toString().contains("Inert home"));
        assertFalse(ArmExperimentService.isRunning());
    }

    @Test public void explicitFreshChoiceStoresOnlyPanelIdentityAndInvalidatesPreviousBinding() {
        answer = ready(); begin(); finishQuery();
        button("Use this ADT system").performClick();
        AdtPortalSession.Binding first = AdtPortalSession.binding(context);
        assertNotNull(first); assertEquals("system-7", first.systemId); assertEquals("partition-1", first.partitionId);
        assertTrue(AdtPortalSession.valid(context, first));
        assertEquals(Set.of("id", "system", "partition"), bindingPreferences().getAll().keySet());
        assertTrue(screenText().contains("No alarm command was sent"));
        assertEquals(View.GONE, button("Use this ADT system").getVisibility());
        begin(); finishQuery(); button("Use this ADT system").performClick();
        assertFalse(AdtPortalSession.valid(context, first));
        assertTrue(AdtPortalSession.valid(context, AdtPortalSession.binding(context)));
    }

    @Test public void choosingSystemRetiresWebsiteButNativeChecksKeepItsCookiesAndBinding() {
        WebView login = web();
        CookieManager cookies = CookieManager.getInstance();
        cookies.setCookie(AdtPortalSession.COOKIE_ORIGIN, "inert_session=fixture; Path=/; Secure");
        answer = ready(); begin(); finishQuery();
        AdtPortalClient.Session original = queriedSession;
        button("Use this ADT system").performClick();
        AdtPortalSession.Binding selected = AdtPortalSession.binding(context);

        assertNull(web()); assertNull(login.getParent());
        assertTrue(Shadows.shadowOf(login).wasDestroyCalled());
        assertTrue(cookies.getCookie(AdtPortalSession.COOKIE_ORIGIN).contains("inert_session=fixture"));
        assertTrue(AdtPortalSession.valid(context, selected));
        assertEquals(View.VISIBLE, button("Open ADT sign-in").getVisibility());
        assertEquals(1, queries);
        begin(); finishQuery();
        assertNotSame(original, queriedSession); assertEquals(2, queries);
        assertTrue(queriedCookies.contains("inert_session=fixture"));
        assertTrue(button("Use this ADT system").isEnabled());
        assertNull(web()); assertTrue(AdtPortalSession.valid(context, selected));
        assertFalse(ArmExperimentService.isRunning());
    }

    @Test public void unfinishedVerificationKeepsItsWebsiteAcrossBackgroundAndReturn() {
        WebView login = web();
        answer = new AdtPortalClient.Result(AdtPortalClient.Status.VERIFY_LOGIN,
            AlarmStateProtocol.State.UNKNOWN, "", "", "", "", 42);
        begin(); finishQuery();
        controller.pause().stop();
        assertSame(login, web()); assertFalse(Shadows.shadowOf(login).wasDestroyCalled());
        assertTrue(Shadows.shadowOf(login).wasOnPauseCalled());
        controller.restart().start().resume().windowFocusChanged(true); idle();
        assertSame(login, web()); assertTrue(Shadows.shadowOf(login).wasOnResumeCalled());
        assertEquals(View.GONE, button("Open ADT sign-in").getVisibility());
        assertNull(AdtPortalSession.binding(context)); assertEquals(1, queries); assertTrue(work.isEmpty());
    }

    @Test public void completedSetupStaysRetiredOnResumeRecreationAndLaterVisitUntilExplicitSignIn() {
        answer = ready(); begin(); finishQuery(); button("Use this ADT system").performClick();
        AdtPortalSession.Binding selected = AdtPortalSession.binding(context);
        controller.pause(); controller.resume().windowFocusChanged(true); idle();
        assertNull(web());
        controller.recreate(); activity = controller.get(); controller.windowFocusChanged(true); idle();
        assertNull(web()); assertTrue(button("Check live status").isEnabled());
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(AdtPortalSetupActivity.class).setup().visible();
        activity = controller.get(); controller.windowFocusChanged(true); idle();
        assertNull(web()); assertEquals(1, queries); assertTrue(work.isEmpty());
        assertTrue(AdtPortalSession.valid(context, selected));

        button("Open ADT sign-in").performClick();
        assertNotNull(web());
        assertEquals(AdtPortalSetupActivity.LOGIN_URL, Shadows.shadowOf(web()).getLastLoadedUrl());
        assertFalse(web().getSettings().getAllowFileAccess());
        assertTrue(AdtPortalSession.valid(context, selected));
        assertEquals(1, queries); assertTrue(work.isEmpty());
    }

    @Test public void retiredWebsiteCallbacksCannotCancelNativeCheckOrReplacementLoginResult() {
        WebView old = web(); WebViewClient navigation = old.getWebViewClient();
        answer = ready(); begin(); finishQuery(); button("Use this ADT system").performClick();
        String selected = screenText();
        navigation.onPageStarted(old, "https://www.alarm.com/login.aspx", null);
        assertTrue(navigation.shouldOverrideUrlLoading(old, "https://www.alarm.com/login.aspx"));
        assertEquals(selected, screenText());
        begin();
        navigation.onPageStarted(old, "https://unsupported.invalid/", null);
        finishQuery();
        assertTrue(button("Use this ADT system").isEnabled());
        button("Open ADT sign-in").performClick();
        assertNotSame(old, web());
        begin(); finishQuery();
        navigation.onPageStarted(old, "https://www.alarm.com/login.aspx", null);
        assertTrue(button("Use this ADT system").isEnabled());
        assertEquals(3, queries);
    }

    @Test public void expiredSavedSessionOffersExplicitSignInWithoutOpeningWebsiteAutomatically() {
        answer = ready(); begin(); finishQuery(); button("Use this ADT system").performClick();
        AdtPortalSession.Binding selected = AdtPortalSession.binding(context);
        for (AdtPortalClient.Status status : new AdtPortalClient.Status[]{
                AdtPortalClient.Status.LOGIN_REQUIRED, AdtPortalClient.Status.VERIFY_LOGIN}) {
            answer = new AdtPortalClient.Result(status, AlarmStateProtocol.State.UNKNOWN, "", "", "", "", 42);
            begin(); finishQuery();
            assertTrue(screenText().contains("Tap Open ADT sign-in"));
            assertTrue(button("Open ADT sign-in").isEnabled()); assertNull(web());
            assertEquals(View.GONE, button("Use this ADT system").getVisibility());
            assertTrue(AdtPortalSession.valid(context, selected));
        }
    }

    @Test public void timeoutCannotBeReplacedByAnOldSuccessfulResultOrAutoRetry() {
        context.getSharedPreferences("adt_portal_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("code", "IDENTITIES/HTTP/500").commit();
        answer = ready(); begin();
        assertEquals("UI/CHECKING", context.getSharedPreferences("adt_portal_diagnostics", Context.MODE_PRIVATE).getString("code", ""));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        assertEquals("UI/TIMEOUT", context.getSharedPreferences("adt_portal_diagnostics", Context.MODE_PRIVATE).getString("code", ""));
        assertTrue(screenText().contains("10 seconds")); assertTrue(button("Check live status").isEnabled());
        finishQuery(); // The expired FutureTask cannot even begin a query after timeout.
        assertEquals(0, queries); assertNull(AdtPortalSession.binding(context));
        assertEquals(View.GONE, button("Use this ADT system").getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        assertEquals(0, queries); assertTrue(work.isEmpty());
    }

    @Test public void completedWorkerAnswerAfterPauseCannotBecomeAChoiceOnReturn() {
        answer = ready(); begin();
        work.remove().run(); // Posts the answer, but the UI has not accepted it.
        controller.pause(); idle(); controller.resume().windowFocusChanged(true); idle();
        assertEquals(1, queries); assertNull(AdtPortalSession.binding(context));
        assertEquals(View.GONE, button("Use this ADT system").getVisibility());
        assertTrue(screenText().contains("Tap Check live status"));
        assertEquals("UI/CANCELLED", context.getSharedPreferences("adt_portal_diagnostics", Context.MODE_PRIVATE).getString("code", ""));
        assertThrows(IllegalStateException.class, () -> queriedSession.storeCookie("stale_session=ignored; Path=/; Secure"));
    }

    @Test public void newNavigationAndAcceptedCompletionCloseOnlyTheirOwnQueryCookieWriter() {
        answer = ready(); begin(); work.remove().run();
        AdtPortalClient.Session cancelled = queriedSession;
        web().getWebViewClient().onPageStarted(web(), "https://www.alarm.com/login.aspx", null);
        CookieManager.getInstance().setCookie(AdtPortalSession.COOKIE_ORIGIN, "inert_session=new_login; Path=/; Secure");
        assertThrows(IllegalStateException.class, () -> cancelled.storeCookie("inert_session=old_query; Path=/; Secure"));
        idle();
        assertEquals(View.GONE, button("Use this ADT system").getVisibility());
        begin(); finishQuery();
        assertNotSame(cancelled, queriedSession);
        assertTrue(queriedCookies.contains("inert_session=new_login"));
        assertTrue(button("Use this ADT system").isEnabled());
        assertThrows(IllegalStateException.class, () -> queriedSession.storeCookie("inert_session=late_query; Path=/; Secure"));
        assertTrue(CookieManager.getInstance().getCookie(AdtPortalSession.COOKIE_ORIGIN).contains("inert_session=new_login"));
    }

    @Test public void expiryLockAndNewNavigationInvalidateChoiceWithoutSaving() {
        answer = ready(); begin(); finishQuery();
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        button("Use this ADT system").performClick(); assertNull(AdtPortalSession.binding(context));
        Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60));
        button("Use this ADT system").performClick(); assertNull(AdtPortalSession.binding(context));
        begin(); finishQuery();
        web().getWebViewClient().onPageStarted(web(), "https://www.alarm.com/login.aspx", null);
        button("Use this ADT system").performClick(); assertNull(AdtPortalSession.binding(context));
        assertEquals(View.GONE, button("Use this ADT system").getVisibility());
    }

    @Test public void loginVerificationAmbiguityBusyAndUnsupportedNeverShowActionableState() {
        for (AdtPortalClient.Status status : AdtPortalClient.Status.values()) {
            if (status == AdtPortalClient.Status.READY) continue;
            answer = new AdtPortalClient.Result(status, AlarmStateProtocol.State.UNKNOWN, "", "", "", "", 42);
            begin(); finishQuery();
            assertNull(AdtPortalSession.binding(context));
            assertEquals(View.GONE, button("Use this ADT system").getVisibility());
            assertFalse(screenText().contains("ADT reports Disarmed"));
        }
    }

    @Test public void navigationIsExactHttpsAndWebViewCannotAccessLocalFiles() {
        for (String allowed : new String[]{"https://smartservices.adt.co.uk/", "https://www.alarm.com/login.aspx",
                "https://alarm.com/", "https://www.alarm.com:443/web/"}) assertTrue(AdtPortalSetupActivity.allowedNavigation(allowed));
        for (String denied : new String[]{null, "http://www.alarm.com/", "https://www.alarm.com.evil.test/",
                "https://evil.test/?next=https://www.alarm.com", "https://user@www.alarm.com/",
                "https://www.alarm.com:8443/", "file:///sdcard/file", "content://file", "javascript:alert(1)",
                "intent://login", "https://smartservices.adt.co.uk.evil.test/"}) {
            assertFalse(AdtPortalSetupActivity.allowedNavigation(denied));
            if (denied != null) assertTrue(web().getWebViewClient().shouldOverrideUrlLoading(web(), denied));
        }
        assertTrue(screenText().contains("unsupported navigation was blocked"));
        WebSettings settings = web().getSettings();
        assertTrue(settings.getJavaScriptEnabled()); assertTrue(settings.getDomStorageEnabled());
        assertFalse(settings.getAllowFileAccess()); assertFalse(settings.getAllowContentAccess());
        assertFalse(settings.getAllowFileAccessFromFileURLs()); assertFalse(settings.getAllowUniversalAccessFromFileURLs());
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.getMixedContentMode());
        assertEquals(0, queries); assertNull(AdtPortalSession.binding(context));
    }

    private AdtPortalClient.Result ready() {
        return new AdtPortalClient.Result(AdtPortalClient.Status.READY, AlarmStateProtocol.State.DISARMED,
            "system-7", "partition-1", "Inert home", "Inert partition", 250);
    }
    private void begin() { assertTrue(button("Check live status").isEnabled()); button("Check live status").performClick(); }
    private void finishQuery() { work.remove().run(); idle(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private Button button(String text) {
        Button result = find(activity.getWindow().getDecorView(), Button.class, text); assertNotNull(result); return result;
    }
    private WebView web() { return find(activity.getWindow().getDecorView(), WebView.class, null); }
    private SharedPreferences bindingPreferences() { return context.getSharedPreferences("adt_portal_binding", Context.MODE_PRIVATE); }
    private String screenText() { return collectText(activity.getWindow().getDecorView()); }
    private static String collectText(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            text.append(collectText(((ViewGroup) view).getChildAt(i)));
        return text.toString();
    }
    private static <T extends View> T find(View view, Class<T> type, String text) {
        if (type.isInstance(view) && (text == null || view instanceof TextView && text.contentEquals(((TextView) view).getText())))
            return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), type, text); if (found != null) return found;
        }
        return null;
    }
}
