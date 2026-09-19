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

    /** Create on the UI thread after the WebView has been initialized; use from the query worker. */
    static AdtPortalClient.Session session(Context context) {
        CookieManager manager = CookieManager.getInstance();
        String agent = WebSettings.getDefaultUserAgent(context.getApplicationContext());
        return new AdtPortalClient.Session() {
            private boolean changed;
            @Override public String cookies() { return manager.getCookie(COOKIE_ORIGIN); }
            @Override public String userAgent() { return agent; }
            @Override public void storeCookie(String value) {
                if (value != null && !value.isEmpty()) { manager.setCookie(COOKIE_ORIGIN, value); changed = true; }
            }
            @Override public void persist() {
                if (changed) { manager.flush(); changed = false; }
            }
        };
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
