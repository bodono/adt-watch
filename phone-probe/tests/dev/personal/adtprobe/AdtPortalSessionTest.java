package dev.personal.adtprobe;

import android.content.Context;
import android.webkit.CookieManager;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Inert cookie jars only; no login, network or alarm path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AdtPortalSessionTest {
    private static final String ADT = "https://smartservices.adt.co.uk", API = "/web/api/identities";
    private final Context context = RuntimeEnvironment.getApplication();
    @Before public void clear() { reset(); }
    @After public void restore() { reset(); }
    private void reset() {
        CookieManager.getInstance().removeAllCookies(null);
        context.getSharedPreferences("adt_portal_session", Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** Robolectric's CookieManager ignores cookie paths, so a path-scoped jar is modelled explicitly. */
    @Test public void sessionOriginIsDetectedAtTheApiUrlAndPrefersTheVerifiedHost() {
        Map<String, String> jar = new HashMap<>();
        AdtPortalSession.CookieLookup lookup = jar::get;
        assertEquals(AdtPortalClient.ORIGIN, AdtPortalSession.sessionOrigin(lookup, null));
        jar.put(ADT + API, "afg=inert-key; Secure");
        assertEquals("A key visible only under /web is found at the API URL", ADT, AdtPortalSession.sessionOrigin(lookup, null));
        jar.put(AdtPortalClient.ORIGIN, "afg=root-only-key");
        assertEquals("A key visible only at the origin root is no session for the API", ADT, AdtPortalSession.sessionOrigin(lookup, null));
        jar.put(AdtPortalClient.ORIGIN + API, "other=value; Secure");
        assertEquals("A jar without the request key does not win", ADT, AdtPortalSession.sessionOrigin(lookup, null));
        jar.put(AdtPortalClient.ORIGIN + API, "afg=inert-key-2; Secure");
        assertEquals("With keys on both hosts alarm.com is the default...", AdtPortalClient.ORIGIN, AdtPortalSession.sessionOrigin(lookup, null));
        assertEquals("...unless the ADT host last completed an authenticated read", ADT, AdtPortalSession.sessionOrigin(lookup, ADT));
        jar.remove(ADT + API);
        assertEquals("A verified host whose key is gone is not used", AdtPortalClient.ORIGIN, AdtPortalSession.sessionOrigin(lookup, ADT));
        assertEquals("An origin outside the allow-list is never used", AdtPortalClient.ORIGIN,
            AdtPortalSession.sessionOrigin(lookup, "https://evil.test"));
    }

    @Test public void theVerifiedOriginIsRememberedOnlyFromTheAllowListAndFeedsTheRealJar() {
        assertNull(AdtPortalSession.verifiedOrigin(context));
        AdtPortalSession.recordVerifiedOrigin(context, "https://evil.test");
        AdtPortalSession.recordVerifiedOrigin(context, null);
        assertNull(AdtPortalSession.verifiedOrigin(context));
        AdtPortalSession.recordVerifiedOrigin(context, ADT);
        assertEquals(ADT, AdtPortalSession.verifiedOrigin(context));
        CookieManager manager = CookieManager.getInstance();
        manager.setCookie(ADT + API, "afg=inert-key; Secure");
        manager.setCookie(AdtPortalClient.ORIGIN + API, "afg=inert-key-2; Secure");
        assertEquals(ADT, AdtPortalSession.sessionOrigin(manager::getCookie, AdtPortalSession.verifiedOrigin(context)));
        assertEquals(ADT, AdtPortalSession.session(context).origin());
        manager.removeAllCookies(null);
        assertEquals(AdtPortalClient.ORIGIN, AdtPortalSession.session(context).origin());
    }

    @Test public void requestKeyDetectionIsExactOnTheCookieName() {
        assertFalse(AdtPortalSession.hasRequestKey(null));
        assertFalse(AdtPortalSession.hasRequestKey(""));
        assertFalse(AdtPortalSession.hasRequestKey("afgx=1; xafg=2"));
        assertTrue(AdtPortalSession.hasRequestKey("session=abc; afg=key"));
        assertTrue(AdtPortalSession.hasRequestKey(" afg = key "));
    }
}
