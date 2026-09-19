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
        @Override public synchronized void storeCookie(String value) { requireActive(); delegate.storeCookie(value); }
        @Override public synchronized void storeCookie(String url, String value) { requireActive(); delegate.storeCookie(url, value); }
        @Override public synchronized void persist() { if (active) delegate.persist(); }
    }

    /** Create on the UI thread after the WebView has been initialized; use from the query worker. */
    static AdtPortalClient.Session session(Context context) {
        CookieManager manager = CookieManager.getInstance();
        String agent = WebSettings.getDefaultUserAgent(context.getApplicationContext());
        return new AdtPortalClient.Session() {
            private boolean changed;
            @Override public String cookies() { return cookies(COOKIE_ORIGIN + "/web/api/identities"); }
            @Override public String cookies(String requestUrl) {
                requireApiUrl(requestUrl);
                return manager.getCookie(requestUrl);
            }
            @Override public String userAgent() { return agent; }
            @Override public void storeCookie(String value) { storeCookie(COOKIE_ORIGIN + "/web/api/identities", value); }
            @Override public void storeCookie(String responseUrl, String value) {
                requireApiUrl(responseUrl);
                if (value != null && !value.isEmpty()) { manager.setCookie(responseUrl, value); changed = true; }
            }
            @Override public void persist() {
                if (changed) { manager.flush(); changed = false; }
            }
        };
    }

    private static void requireApiUrl(String url) {
        if (url == null || !url.startsWith(COOKIE_ORIGIN + "/web/api/"))
            throw new IllegalArgumentException("Unexpected cookie scope");
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
}
