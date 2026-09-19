package dev.personal.adtprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import java.util.UUID;

/** The helper's own WebView sign-in and explicit system choice; no ADT-app session access. */
final class AdtPortalSession {
    static final String COOKIE_ORIGIN = "https://www.alarm.com";
    private static final String PREFERENCES = "adt_portal_binding";
    private static final String SESSION_PREFERENCES = "adt_portal_session";
    /** The API URL whose cookie visibility decides where the sign-in lives; fetch uses the same paths. */
    private static final String API_PROBE = "/web/api/identities";
    /** Cookie visibility for one exact URL, as CookieManager.getCookie answers it. */
    interface CookieLookup { String cookie(String url); }

    private AdtPortalSession() { }

    /** A cancelled setup read must not rotate cookies after a newer sign-in begins. */
    static QuerySession cancellable(AdtPortalClient.Session session) { return new QuerySession(session); }

    static final class QuerySession implements AdtPortalClient.Session {
        private final AdtPortalClient.Session delegate;
        private boolean active = true;
        private QuerySession(AdtPortalClient.Session delegate) { this.delegate = delegate; }
        synchronized void invalidate() { active = false; }
        private void requireActive() {
            if (!active) throw new IllegalStateException("ADT status check cancelled");
        }
        @Override public synchronized String cookies() { requireActive(); return delegate.cookies(); }
        @Override public synchronized String cookies(String url) { requireActive(); return delegate.cookies(url); }
        @Override public synchronized String userAgent() { requireActive(); return delegate.userAgent(); }
        @Override public synchronized String origin() { requireActive(); return delegate.origin(); }
        @Override public synchronized void storeCookie(String value) { requireActive(); delegate.storeCookie(value); }
        @Override public synchronized void storeCookie(String url, String value) { requireActive(); delegate.storeCookie(url, value); }
        @Override public synchronized void persist() { if (active) delegate.persist(); }
    }

    /** Create on the UI thread after the WebView has been initialized; use from the query worker. */
    static AdtPortalClient.Session session(Context context) {
        CookieManager manager = CookieManager.getInstance();
        String origin = sessionOrigin(manager::getCookie, verifiedOrigin(context));
        return AdtSessionRecovery.guardSession(session(context, manager, origin, false));
    }

    /** Separate, fixed Alarm.com login scope. The status client's requests remain GET-only. */
    static AdtPortalClient.Session loginSession(Context context) {
        return session(context, CookieManager.getInstance(), COOKIE_ORIGIN, true);
    }

    private static AdtPortalClient.Session session(Context context, CookieManager manager, String origin, boolean login) {
        String agent = WebSettings.getDefaultUserAgent(context.getApplicationContext());
        return new AdtPortalClient.Session() {
            private boolean changed;
            @Override public String origin() { return origin; }
            @Override public String cookies() { return cookies(origin + "/web/api/identities"); }
            @Override public String cookies(String requestUrl) {
                requireSessionUrl(origin, requestUrl, login);
                return manager.getCookie(requestUrl);
            }
            @Override public String userAgent() { return agent; }
            @Override public void storeCookie(String value) { storeCookie(origin + "/web/api/identities", value); }
            @Override public void storeCookie(String responseUrl, String value) {
                requireSessionUrl(origin, responseUrl, login);
                if (value != null && !value.isEmpty()) { manager.setCookie(responseUrl, value); changed = true; }
            }
            @Override public void persist() {
                if (changed) { manager.flush(); changed = false; }
            }
        };
    }

    /**
     * ADT UK signs in on its own branded host. Whether that session is then held on alarm.com or on
     * the ADT host is not known until a real account is tried. The jar is consulted at the exact API
     * URL a read would use, because a session cookie scoped to /web is invisible at the origin root.
     * A key alone cannot tell which of two retained host sessions is signed in, so the host that
     * last completed an authenticated read is tried first while it still holds a key; otherwise the
     * first allowed origin holding one, preferring alarm.com.
     */
    static String sessionOrigin(CookieLookup cookies, String verified) {
        if (verified != null && AdtPortalClient.ORIGINS.contains(verified) && hasRequestKey(cookies.cookie(verified + API_PROBE)))
            return verified;
        for (String origin : AdtPortalClient.ORIGINS) if (hasRequestKey(cookies.cookie(origin + API_PROBE))) return origin;
        return COOKIE_ORIGIN;
    }

    /** The allowed origin that last answered an authenticated read, or null. */
    static String verifiedOrigin(Context context) {
        String value = sessionPreferences(context).getString("verifiedOrigin", null);
        return value != null && AdtPortalClient.ORIGINS.contains(value) ? value : null;
    }

    static void recordVerifiedOrigin(Context context, String origin) {
        if (origin != null && AdtPortalClient.ORIGINS.contains(origin))
            sessionPreferences(context).edit().putString("verifiedOrigin", origin).apply();
    }

    static boolean hasRequestKey(String cookies) {
        if (cookies == null) return false;
        for (String cookie : cookies.split(";")) {
            int equals = cookie.indexOf('=');
            if (equals >= 0 && "afg".equals(cookie.substring(0, equals).trim())) return true;
        }
        return false;
    }

    private static void requireApiUrl(String origin, String url) {
        if (url == null || !url.startsWith(origin + "/web/api/"))
            throw new IllegalArgumentException("Unexpected cookie scope");
    }

    private static void requireSessionUrl(String origin, String url, boolean login) {
        if (login && (url != null && (url.equals(origin + "/login") || url.equals(origin + "/login.aspx")
                || url.equals(origin + "/web/Default.aspx")))) return;
        requireApiUrl(origin, url);
    }

    static final class Binding {
        final String id, systemId, partitionId;
        private Binding(String id, String systemId, String partitionId) {
            this.id = id; this.systemId = systemId; this.partitionId = partitionId;
        }
    }

    static Binding binding(Context context) {
        SharedPreferences stored = preferences(context);
        String system = stored.getString("system", ""), partition = stored.getString("partition", "");
        String id = stored.getString("id", "");
        return AlarmStateProtocol.uuid(id) && validId(system) && validId(partition) ? new Binding(id, system, partition) : null;
    }

    static boolean valid(Context context, Binding expected) {
        Binding current = binding(context);
        return expected != null && current != null && current.id.equals(expected.id)
            && current.systemId.equals(expected.systemId) && current.partitionId.equals(expected.partitionId);
    }

    /** Only the setup screen's explicit, fresh "Use this ADT system" click calls this. */
    static boolean bind(Context context, String systemId, String partitionId) {
        return validId(systemId) && validId(partitionId) && preferences(context).edit().clear()
            .putString("id", UUID.randomUUID().toString())
            .putString("system", systemId).putString("partition", partitionId).commit();
    }

    static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
    private static SharedPreferences sessionPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE);
    }
}
