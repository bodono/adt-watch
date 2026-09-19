package dev.personal.adtprobe;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** A login attempt's isolated jar. Only a verified matching-home read authorizes commit(). */
final class AdtLoginSession implements AdtPortalClient.Session {
    private static final String ORIGIN = AdtLoginClient.ORIGIN;
    private static final String SEED_URL = ORIGIN + AdtLoginClient.POST_PATH;
    private static final String TRUST = "twoFactorAuthenticationId";
    private static final int MAX_COOKIE = 8192, MAX_HEADER = 16_384, MAX_COOKIES = 64;
    private static final int MAX_MUTATIONS = 128, MAX_STAGED_CHARS = 65_536;
    private final AdtPortalClient.Session persistent;
    private final CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final List<Mutation> staged = new ArrayList<>();
    private int stagedChars;
    private boolean committed;

    AdtLoginSession(AdtPortalClient.Session persistent) {
        if (persistent == null || !ORIGIN.equals(persistent.origin())) throw invalid();
        this.persistent = persistent;
        String header = persistent.cookies(SEED_URL);
        if (header == null || header.isEmpty()) return;
        if (!ascii(header, MAX_HEADER)) throw invalid();
        String trust = null;
        for (String pair : header.split(";")) {
            int equals = pair.indexOf('=');
            if (equals < 0 || !TRUST.equals(pair.substring(0, equals).trim())) continue;
            if (trust != null) throw invalid();
            trust = value(pair.substring(equals + 1).trim());
        }
        if (trust != null && !trust.isEmpty()) {
            // Cookie request headers do not carry expiry/path attributes. This transient seed is
            // only a remembered-device hint, never an authenticated session or proof of login.
            put(SEED_URL, TRUST + "=" + trust + "; Path=/; Secure; HttpOnly");
        }
    }

    @Override public String origin() { requireCurrent(); return ORIGIN; }
    @Override public String userAgent() { requireCurrent(); return persistent.userAgent(); }
    @Override public String cookies() { return cookies(ORIGIN + "/web/api/identities"); }
    @Override public synchronized String cookies(String url) {
        requireCurrent(); URI uri = approved(url);
        try {
            Map<String, List<String>> headers = jar.get(uri, Collections.emptyMap());
            List<String> parts = new ArrayList<>(); int total = 0;
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (!"Cookie".equalsIgnoreCase(header.getKey())) continue;
                for (String line : header.getValue()) for (String raw : line.split(";")) {
                    String part = raw.trim(); int equals = part.indexOf('=');
                    if (equals < 1 || part.charAt(0) == '$') continue;
                    String name = part.substring(0, equals);
                    if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) throw invalid();
                    String pair = name + "=" + value(part.substring(equals + 1));
                    total += pair.length() + (parts.isEmpty() ? 0 : 2);
                    if (total > MAX_HEADER || parts.size() >= MAX_COOKIES) throw invalid();
                    parts.add(pair);
                }
            }
            return String.join("; ", parts);
        } catch (IOException ignored) { throw invalid(); }
    }
    @Override public void storeCookie(String cookie) { storeCookie(SEED_URL, cookie); }
    @Override public synchronized void storeCookie(String responseUrl, String cookie) {
        requireCurrent(); approved(responseUrl);
        if (committed || !ascii(cookie, MAX_COOKIE) || staged.size() >= MAX_MUTATIONS
                || stagedChars > MAX_STAGED_CHARS - cookie.length()) throw invalid();
        validateSetCookie(cookie);
        put(responseUrl, cookie);
        if (jar.getCookieStore().getCookies().size() > MAX_COOKIES) throw invalid();
        staged.add(new Mutation(responseUrl, cookie)); stagedChars += cookie.length();
    }
    /** Login/status client finally blocks cannot publish tentative authentication cookies. */
    @Override public void persist() { }

    /** Caller must hold its recovery-generation guard and have verified the saved home with this jar. */
    synchronized void commit() {
        requireCurrent();
        if (committed) throw invalid();
        for (Mutation mutation : staged) persistent.storeCookie(mutation.url, mutation.cookie);
        persistent.persist(); committed = true; staged.clear(); stagedChars = 0;
    }
    @Override public String toString() { return "Isolated ADT login session (cookies redacted)"; }

    private void requireCurrent() {
        // A wrapped persistent session can revoke a superseded attempt even before it commits.
        if (!ORIGIN.equals(persistent.origin())) throw invalid();
    }
    private void put(String url, String cookie) {
        try { jar.put(approved(url), Collections.singletonMap("Set-Cookie", Collections.singletonList(cookie))); }
        catch (IOException | IllegalArgumentException ignored) { throw invalid(); }
    }
    private static void validateSetCookie(String raw) {
        List<HttpCookie> parsed;
        try { parsed = HttpCookie.parse(raw); }
        catch (IllegalArgumentException ignored) { throw invalid(); }
        if (parsed.size() != 1) throw invalid();
        HttpCookie cookie = parsed.get(0);
        if (!cookie.getName().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) throw invalid();
        value(cookie.getValue());
        String domain = cookie.getDomain(), path = cookie.getPath();
        if (domain != null && !domain.equalsIgnoreCase("www.alarm.com") && !domain.equalsIgnoreCase(".www.alarm.com")
                && !domain.equalsIgnoreCase("alarm.com") && !domain.equalsIgnoreCase(".alarm.com")) throw invalid();
        if (path != null && (!path.startsWith("/") || path.length() > 512)) throw invalid();
    }
    private static URI approved(String url) {
        if (url == null || url.length() > 2048) throw invalid();
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || !"www.alarm.com".equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || path == null || !path.equals(uri.getPath()) || path.contains("//") || path.contains("/../") || path.contains("/./")
                    || !("/login".equals(path) || "/login.aspx".equals(path) || "/web/Default.aspx".equals(path)
                        || "/web/api/identities".equals(path)
                        || path.matches("/web/api/systems/systems/[A-Za-z0-9_-]{1,128}")
                        || path.matches("/web/api/devices/partitions/[A-Za-z0-9_-]{1,128}"))) throw invalid();
            return uri;
        } catch (IllegalArgumentException ignored) { throw invalid(); }
    }
    private static String value(String raw) {
        if (raw == null || raw.length() > MAX_COOKIE) throw invalid();
        String result = raw;
        // java.net may serialize a Max-Age cookie as RFC2965 version 1. The portal expects the
        // ordinary unquoted cookie value used by Android WebView and the ajaxrequestuniquekey.
        if (result.length() >= 2 && result.charAt(0) == '"' && result.charAt(result.length() - 1) == '"')
            result = result.substring(1, result.length() - 1);
        for (int i = 0; i < result.length(); i++) {
            char next = result.charAt(i);
            if (next < 33 || next > 126 || next == '"' || next == ',' || next == ';' || next == '\\') throw invalid();
        }
        return result;
    }
    private static boolean ascii(String value, int bound) {
        if (value == null || value.length() > bound) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) return false;
        return true;
    }
    private static IllegalStateException invalid() { return new IllegalStateException("Invalid isolated ADT login session"); }
    private static final class Mutation {
        final String url, cookie;
        Mutation(String url, String cookie) { this.url = url; this.cookie = cookie; }
        @Override public String toString() { return "ADT cookie mutation (redacted)"; }
    }
}
