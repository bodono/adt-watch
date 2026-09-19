package dev.personal.adtprobe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** In-memory cookies only: no network, WebView, credentials or account access. */
public final class AdtLoginSessionTest {
    private static final String ORIGIN = AdtLoginClient.ORIGIN;
    private static final String LOGIN = ORIGIN + "/login", POST = ORIGIN + "/web/Default.aspx";
    private static final String API = ORIGIN + "/web/api/identities";

    @Test public void existingAuthenticationIsExcludedWhileOnlyTrustIsRetained() {
        PersistentSession persistent = new PersistentSession();
        persistent.existing = "afg=old-auth; .ASPXAUTH=old-login; other=private; twoFactorAuthenticationId=inert-trust";
        AdtLoginSession session = new AdtLoginSession(persistent);
        assertEquals(Arrays.asList(POST), persistent.readUrls);
        assertEquals("twoFactorAuthenticationId=inert-trust", session.cookies(POST));
        assertEquals("twoFactorAuthenticationId=inert-trust", session.cookies(API));
        assertEquals(ORIGIN, session.origin()); assertEquals("inert-user-agent", session.userAgent());
        assertFalse(session.cookies().contains("old-auth")); assertFalse(session.cookies().contains("old-login"));
        assertFalse(session.cookies().contains("private")); assertTrue(persistent.writes.isEmpty());
        assertFalse(session.toString().contains("inert-trust"));
    }
    @Test public void noTrustMeansAnEmptyNewJarEvenWhenOldAuthenticationWorks() {
        PersistentSession persistent = new PersistentSession(); persistent.existing = "afg=old; .ASPXAUTH=still-valid";
        AdtLoginSession session = new AdtLoginSession(persistent);
        assertEquals("", session.cookies(LOGIN)); assertEquals("", session.cookies(POST)); assertEquals("", session.cookies(API));
        persistent.existing = null;
        assertEquals("", new AdtLoginSession(persistent).cookies());
    }
    @Test public void rotationsAreVisiblePrivatelyAndCommitWritesExactResponseUrlsInOrder() {
        PersistentSession persistent = new PersistentSession(); AdtLoginSession session = new AdtLoginSession(persistent);
        session.storeCookie(LOGIN, "form=inert-form; Path=/; Secure");
        session.storeCookie(POST, "afg=inert-first; Path=/web; Secure");
        session.storeCookie(API, "afg=inert-new; Path=/web; Secure; Max-Age=3600");
        assertTrue(session.cookies(POST).contains("form=inert-form"));
        assertTrue(session.cookies(API).contains("afg=inert-new")); assertFalse(session.cookies(API).contains("inert-first"));
        assertFalse("API request key is not surrounded by RFC2965 quotes", session.cookies(API).contains("\"inert-new\""));
        assertFalse("Metadata never becomes a portal cookie", session.cookies(API).contains("$"));
        assertFalse("Path-scoped auth stays outside /login", session.cookies(LOGIN).contains("afg="));
        session.persist(); assertTrue(persistent.writes.isEmpty()); assertEquals(0, persistent.flushes);
        session.commit();
        assertEquals(Arrays.asList(LOGIN, POST, API), persistent.writeUrls);
        assertEquals(Arrays.asList("form=inert-form; Path=/; Secure", "afg=inert-first; Path=/web; Secure",
            "afg=inert-new; Path=/web; Secure; Max-Age=3600"), persistent.writes);
        assertEquals(1, persistent.flushes);
        refuses(session::commit);
        refuses(() -> session.storeCookie(API, "late=no; Path=/"));
    }
    @Test public void failedAttemptAndFinallyPersistNeverTouchOwnersExistingSession() {
        PersistentSession persistent = new PersistentSession(); persistent.existing = "afg=old; .ASPXAUTH=old; twoFactorAuthenticationId=old-trust";
        String before = persistent.existing;
        AdtLoginSession session = new AdtLoginSession(persistent);
        session.storeCookie(POST, "afg=rejected-attempt; Path=/web");
        session.storeCookie(POST, ".ASPXAUTH=; Path=/; Max-Age=0");
        session.persist(); session.persist();
        assertTrue(persistent.writes.isEmpty()); assertEquals(0, persistent.flushes); assertEquals(before, persistent.existing);
    }
    @Test public void deletionAndExpiredCookiesDoNotAuthenticateStagingButArePreservedForCommit() {
        PersistentSession persistent = new PersistentSession(); AdtLoginSession session = new AdtLoginSession(persistent);
        session.storeCookie(POST, "afg=temporary; Path=/web");
        assertTrue(session.cookies(API).contains("afg=temporary"));
        session.storeCookie(POST, "afg=; Path=/web; Max-Age=0");
        session.storeCookie(POST, "expired=old; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
        assertFalse(session.cookies(API).contains("afg=")); assertFalse(session.cookies(API).contains("expired="));
        session.commit();
        assertEquals(3, persistent.writes.size()); assertTrue(persistent.writes.get(1).contains("Max-Age=0"));
        assertTrue(persistent.writes.get(2).contains("Expires="));
    }
    @Test public void exactUrlsOnlyAndForeignCookieDomainsAreRejectedWithoutPersistentWrites() {
        PersistentSession persistent = new PersistentSession(); AdtLoginSession session = new AdtLoginSession(persistent);
        for (String url : Arrays.asList("http://www.alarm.com/login", "https://evil.test/login", "https://smartservices.adt.co.uk/login",
                "https://www.alarm.com@evil.test/login", ORIGIN + "/web/system/home", API + "?secret=1", API + "#fragment",
                ORIGIN + "/web/api/../login", ORIGIN + "/web/api/devices/partitions/%2e%2e", ORIGIN + "/web/api/anything")) {
            refuses(() -> session.cookies(url)); refuses(() -> session.storeCookie(url, "afg=inert; Path=/"));
        }
        refuses(() -> session.storeCookie(POST, "afg=inert; Domain=evil.test; Path=/"));
        assertTrue(persistent.writes.isEmpty());
        session.storeCookie(POST, "afg=permitted; Domain=.alarm.com; Path=/web; Secure");
        assertTrue(session.cookies(ORIGIN + "/web/api/devices/partitions/inert-id").contains("afg=permitted"));
        assertTrue(session.cookies(ORIGIN + "/web/api/systems/systems/inert-system").contains("afg=permitted"));
    }
    @Test public void duplicateTrustAndUnsafeOrOversizedCookiesAreRejectedWithRedactedErrors() {
        PersistentSession persistent = new PersistentSession();
        persistent.existing = "twoFactorAuthenticationId=inert-a; twoFactorAuthenticationId=inert-b";
        refuses(() -> new AdtLoginSession(persistent));
        persistent.existing = "twoFactorAuthenticationId=inert\r\nprivate";
        refuses(() -> new AdtLoginSession(persistent));
        persistent.existing = "twoFactorAuthenticationId=inert";
        AdtLoginSession session = new AdtLoginSession(persistent);
        refuses(() -> session.storeCookie(POST, "afg=inert\r\nprivate"));
        refuses(() -> session.storeCookie(POST, "afg=" + "x".repeat(8192)));
        for (int i = 0; i < 127; i++) session.storeCookie(POST, "rotating=" + i + "; Path=/");
        session.storeCookie(POST, "rotating=last; Path=/");
        refuses(() -> session.storeCookie(POST, "rotating=overflow; Path=/"));
        assertTrue(persistent.writes.isEmpty());
    }
    @Test public void supersededPersistentGuardPreventsStagedReadWriteAndCommit() {
        PersistentSession persistent = new PersistentSession(); AdtLoginSession session = new AdtLoginSession(persistent);
        session.storeCookie(POST, "afg=tentative; Path=/web"); persistent.active = false;
        refuses(() -> session.cookies(API)); refuses(() -> session.storeCookie(POST, "afg=late; Path=/web"));
        refuses(session::commit); assertTrue(persistent.writes.isEmpty()); assertEquals(0, persistent.flushes);
    }
    private static void refuses(Runnable action) {
        try { action.run(); fail("Expected session rejection"); }
        catch (IllegalStateException expected) { assertFalse(expected.toString().contains("private")); assertFalse(expected.toString().contains("inert")); }
    }
    private static final class PersistentSession implements AdtPortalClient.Session {
        String existing = "twoFactorAuthenticationId=inert-trust"; boolean active = true; int flushes;
        final List<String> readUrls = new ArrayList<>(), writeUrls = new ArrayList<>(), writes = new ArrayList<>();
        @Override public String origin() { if (!active) throw new IllegalStateException("Session cancelled"); return ORIGIN; }
        @Override public String userAgent() { return "inert-user-agent"; }
        @Override public String cookies() { return existing; }
        @Override public String cookies(String url) { readUrls.add(url); return existing; }
        @Override public void storeCookie(String cookie) { writes.add(cookie); }
        @Override public void storeCookie(String url, String cookie) { writeUrls.add(url); storeCookie(cookie); }
        @Override public void persist() { flushes++; }
    }
}
