package dev.personal.adtprobe;

import android.webkit.CookieManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Inert cookie jars only; no login, network or alarm path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AdtPortalSessionTest {
    @Before public void clear() { CookieManager.getInstance().removeAllCookies(null); }
    @After public void restore() { CookieManager.getInstance().removeAllCookies(null); }

    @Test public void sessionOriginFollowsTheRequestKeyCookieAndPrefersAlarmCom() {
        CookieManager manager = CookieManager.getInstance();
        assertEquals(AdtPortalClient.ORIGIN, AdtPortalSession.sessionOrigin(manager));
        manager.setCookie("https://smartservices.adt.co.uk", "afg=inert-key; Secure");
        assertEquals("https://smartservices.adt.co.uk", AdtPortalSession.sessionOrigin(manager));
        manager.setCookie("https://www.alarm.com", "other=value; Secure");
        assertEquals("A jar without the request key does not win", "https://smartservices.adt.co.uk", AdtPortalSession.sessionOrigin(manager));
        manager.setCookie("https://www.alarm.com", "afg=inert-key-2; Secure");
        assertEquals(AdtPortalClient.ORIGIN, AdtPortalSession.sessionOrigin(manager));
    }

    @Test public void requestKeyDetectionIsExactOnTheCookieName() {
        assertFalse(AdtPortalSession.hasRequestKey(null));
        assertFalse(AdtPortalSession.hasRequestKey(""));
        assertFalse(AdtPortalSession.hasRequestKey("afgx=1; xafg=2"));
        assertTrue(AdtPortalSession.hasRequestKey("session=abc; afg=key"));
        assertTrue(AdtPortalSession.hasRequestKey(" afg = key "));
    }
}
