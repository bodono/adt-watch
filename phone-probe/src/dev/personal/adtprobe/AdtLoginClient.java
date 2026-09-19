package dev.personal.adtprobe;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/** One bounded authentication attempt. This client cannot send an alarm command. */
final class AdtLoginClient {
    static final String ORIGIN = "https://www.alarm.com";
    static final String LOGIN_PATH = "/login", POST_PATH = "/web/Default.aspx";
    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final long MAX_LOGIN_MS = 15_000;
    private static final String USER_FIELD = "ctl00$ContentPlaceHolder1$loginform$txtUserName";
    private static final String PASSWORD_FIELD = "txtPassword";
    private static final List<String> REQUIRED = Arrays.asList("__VIEWSTATE", "__VIEWSTATEGENERATOR",
        "__VIEWSTATEENCRYPTED", "__PREVIOUSPAGE", "__EVENTVALIDATION");
    private static final List<String> OPTIONAL = Arrays.asList("__EVENTTARGET", "__EVENTARGUMENT",
        "loginFolder", "IsFromNewSite", "JavaScriptTest", "ctl00$ContentPlaceHolder1$loginform$hidLoginID");
    private static final Pattern TAG = Pattern.compile("(?is)<(form|input)\\b((?:[^'\">]|\"[^\"]*\"|'[^']*')*)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("\\s*([A-Za-z_:][A-Za-z0-9_.:-]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");
    enum Status { SUBMITTED, REJECTED, VERIFY_LOGIN, UNAVAILABLE, UNSUPPORTED }
    enum Stage { SESSION, FORM, SUBMIT }
    enum Reason { NONE, INPUT, SESSION, FORM, CHALLENGE, HTTP, REDIRECT, TIMEOUT, DNS, TLS, IO, RESPONSE_SIZE, COOKIE_FORMAT, CONTENT_TYPE }
    static final class Result {
        final Status status;
        final long elapsedMillis;
        final Stage stage;
        final Reason reason;
        final int httpStatus;
        Result(Status status, long elapsedMillis, Stage stage, Reason reason, int httpStatus) {
            this.status = status; this.elapsedMillis = Math.max(0, elapsedMillis); this.stage = stage; this.reason = reason;
            this.httpStatus = httpStatus >= 100 && httpStatus <= 599 ? httpStatus : 0;
        }
        String diagnosticCode() { return stage.name() + "/" + reason.name() + (httpStatus == 0 ? "" : "/" + httpStatus); }
        @Override public String toString() { return "ADT login result: " + status; }
    }
    interface Clock { long elapsed(); }
    interface Transport { Response execute(Request request, long deadline) throws IOException; }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    static final class Request {
        final String method, url;
        final Map<String, String> headers;
        final byte[] body;
        private Request(String method, String path, Map<String, String> headers, byte[] body) {
            if (!("GET".equals(method) && loginPath(path) && body == null
                    || "POST".equals(method) && POST_PATH.equals(path) && body != null))
                throw new IllegalArgumentException("Unsupported login request");
            this.method = method; this.url = ORIGIN + path;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers)); this.body = body;
        }
        @Override public String toString() { return "ADT login request (credentials and headers redacted)"; }
    }
    static final class Response {
        final int code;
        final String contentType, location;
        final byte[] body;
        final List<String> setCookies;
        Response(int code, String contentType, byte[] body, List<String> setCookies, String location) {
            this.code = code; this.contentType = contentType; this.body = body; this.location = location;
            this.setCookies = setCookies == null ? Collections.emptyList() : setCookies;
        }
        @Override public String toString() { return "ADT login HTTP " + code + " (content and headers redacted)"; }
    }
    private static final class Failure extends Exception {
        final Status status; final Reason reason;
        Failure(Status status, Reason reason) { super(reason.name()); this.status = status; this.reason = reason; }
    }
    private static final class LimitedIOException extends IOException {
        final Reason reason;
        LimitedIOException(Reason reason) { super(reason.name()); this.reason = reason; }
    }
    private final AdtPortalClient.Session session;
    private final Transport transport;
    private final Clock clock;
    AdtLoginClient(AdtPortalClient.Session session) {
        this.session = session; this.clock = SystemClock::elapsedRealtime; this.transport = new HttpTransport(clock);
    }
    AdtLoginClient(AdtPortalClient.Session session, Transport transport, Clock clock) {
        this.session = session; this.transport = transport; this.clock = clock;
    }

    /**
     * The caller owns its password array. Use an isolated AdtLoginSession: SUBMITTED only means
     * the fixed password POST returned, and requires a fresh scoped status GET in that same jar.
     */
    Result login(String username, char[] password, long requestedDeadline) {
        long started = clock.elapsed();
        long deadline = Math.min(requestedDeadline, started > Long.MAX_VALUE - MAX_LOGIN_MS ? Long.MAX_VALUE : started + MAX_LOGIN_MS);
        Stage stage = Stage.SESSION; int http = 0; byte[] encoded = null;
        try {
            checkDeadline(deadline);
            if (session == null || !ORIGIN.equals(session.origin())) throw new Failure(Status.UNSUPPORTED, Reason.SESSION);
            if (username == null || username.isEmpty() || username.length() > 512 || password == null
                    || password.length == 0 || password.length > 4096) throw new Failure(Status.REJECTED, Reason.INPUT);
            stage = Stage.FORM;
            String path = LOGIN_PATH;
            Response form = null;
            for (int redirects = 0; redirects <= 2; redirects++) {
                form = request("GET", path, null, deadline); http = form.code;
                if (!redirect(http)) break;
                String next = target(path, form.location);
                if (!loginPath(next) || redirects == 2) throw new Failure(Status.UNSUPPORTED, Reason.REDIRECT);
                path = next;
            }
            requireHttp(form.code);
            if (form.code != 200) throw new Failure(Status.UNAVAILABLE, Reason.HTTP);
            Map<String, String> fields = parseForm(html(form), path);
            encoded = encode(fields, username, password);
            checkDeadline(deadline);
            stage = Stage.SUBMIT; http = 0;
            // Never follow a POST redirect: no second credential submission can occur in this call.
            Response answer = request("POST", POST_PATH, encoded, deadline); http = answer.code;
            requireHttp(answer.code);
            if (redirect(answer.code)) {
                // Website destinations vary and may represent home, rejected credentials or MFA.
                // Never inspect, follow or forward credentials to Location. Only the coordinator's
                // authenticated matching-home GET in this isolated jar can establish success.
                return result(Status.SUBMITTED, started, stage, Reason.NONE, http);
            }
            if (answer.code != 200) throw new Failure(Status.UNAVAILABLE, Reason.HTTP);
            String document = html(answer);
            if (challenge(document)) throw new Failure(Status.VERIFY_LOGIN, Reason.CHALLENGE);
            if (document.contains(USER_FIELD) || document.contains("name=\"txtPassword\"") || document.contains("name='txtPassword'"))
                throw new Failure(Status.REJECTED, Reason.FORM);
            // There is no verified positive signature for a 200 login response. Existing valid
            // cookies could make the coordinator's GET pass despite rejected new credentials.
            throw new Failure(Status.UNSUPPORTED, Reason.FORM);
        } catch (Failure failure) { return result(failure.status, started, stage, failure.reason, http); }
        catch (IOException failure) {
            Reason reason = failure instanceof LimitedIOException ? ((LimitedIOException) failure).reason
                : failure instanceof SocketTimeoutException ? Reason.TIMEOUT : failure instanceof UnknownHostException ? Reason.DNS
                : failure instanceof SSLException ? Reason.TLS : Reason.IO;
            return result(Status.UNAVAILABLE, started, stage, reason, http);
        } catch (RuntimeException ignored) { return result(Status.UNSUPPORTED, started, stage, Reason.FORM, http); }
        finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
            if (session != null) try { session.persist(); } catch (RuntimeException ignored) { }
        }
    }
    private Result result(Status status, long started, Stage stage, Reason reason, int http) {
        return new Result(status, clock.elapsed() - started, stage, reason, http);
    }
    private Response request(String method, String path, byte[] body, long deadline) throws Failure, IOException {
        checkDeadline(deadline);
        String ua = session.userAgent(), cookies = session.cookies(ORIGIN + path);
        if (!header(ua, 1024) || ua.isEmpty()) throw new Failure(Status.UNSUPPORTED, Reason.SESSION);
        if (cookies != null && !header(cookies, 16_384)) throw new Failure(Status.UNSUPPORTED, Reason.COOKIE_FORMAT);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", ua); headers.put("Accept", "text/html,application/xhtml+xml");
        headers.put("Cache-Control", "no-cache, no-store"); headers.put("Pragma", "no-cache");
        if (cookies != null && !cookies.isEmpty()) headers.put("Cookie", cookies);
        if (body != null) {
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            headers.put("Referer", ORIGIN + LOGIN_PATH); headers.put("Origin", ORIGIN);
        }
        Response response = transport.execute(new Request(method, path, headers, body), deadline);
        checkDeadline(deadline);
        if (response == null) throw new Failure(Status.UNAVAILABLE, Reason.IO);
        if (response.setCookies.size() > 24) throw new Failure(Status.UNSUPPORTED, Reason.COOKIE_FORMAT);
        for (String cookie : response.setCookies) {
            if (!header(cookie, 8192)) throw new Failure(Status.UNSUPPORTED, Reason.COOKIE_FORMAT);
            session.storeCookie(ORIGIN + path, cookie);
        }
        if (response.body != null && response.body.length > MAX_BODY_BYTES) throw new Failure(Status.UNAVAILABLE, Reason.RESPONSE_SIZE);
        return response;
    }
    private static void requireHttp(int code) throws Failure {
        if (code == 401 || code == 429) throw new Failure(Status.REJECTED, Reason.HTTP);
        if (code == 403 || code == 409 || code == 423) throw new Failure(Status.VERIFY_LOGIN, Reason.HTTP);
        if (code >= 400) throw new Failure(Status.UNAVAILABLE, Reason.HTTP);
    }
    private static String html(Response response) throws Failure {
        String media = response.contentType == null ? "" : response.contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (!"text/html".equals(media) && !"application/xhtml+xml".equals(media)) throw new Failure(Status.UNSUPPORTED, Reason.CONTENT_TYPE);
        if (response.body == null || response.body.length == 0) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body)).toString(); }
        catch (CharacterCodingException ignored) { throw new Failure(Status.UNSUPPORTED, Reason.FORM); }
    }
    private static Map<String, String> parseForm(String source, String path) throws Failure {
        if (challenge(source)) throw new Failure(Status.VERIFY_LOGIN, Reason.CHALLENGE);
        String document = markup(source);
        Map<String, String> fields = new LinkedHashMap<>();
        boolean formSeen = false, user = false, password = false;
        int formEnd = -1;
        Matcher tags = TAG.matcher(document);
        while (tags.find()) {
            Map<String, String> attr = attributes(tags.group(2));
            if ("form".equalsIgnoreCase(tags.group(1))) {
                if (formSeen || !"aspnetForm".equals(attr.get("id")) || !"post".equalsIgnoreCase(attr.get("method")))
                    throw new Failure(Status.UNSUPPORTED, Reason.FORM);
                String action = target(path, attr.get("action"));
                if (!LOGIN_PATH.equals(action) && !POST_PATH.equals(action)) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
                formEnd = document.toLowerCase(Locale.ROOT).indexOf("</form", tags.end());
                if (formEnd < 0) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
                formSeen = true; continue;
            }
            String name = attr.get("name"), type = attr.getOrDefault("type", "text");
            if (name == null) continue;
            if (USER_FIELD.equals(name)) {
                if (!formSeen || tags.start() >= formEnd || user || !"text".equalsIgnoreCase(type)) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
                user = true;
            } else if (PASSWORD_FIELD.equals(name)) {
                if (!formSeen || tags.start() >= formEnd || password || !"password".equalsIgnoreCase(type)) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
                password = true;
            } else if (REQUIRED.contains(name) || OPTIONAL.contains(name)) {
                if (!formSeen || tags.start() >= formEnd || !"hidden".equalsIgnoreCase(type) || fields.containsKey(name)) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
                String value = attr.getOrDefault("value", "");
                if (value.length() > 131_072) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
                fields.put(name, value);
            }
        }
        if (!formSeen || !user || !password || !fields.keySet().containsAll(REQUIRED)) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
        for (String required : REQUIRED) if (!"__VIEWSTATEENCRYPTED".equals(required) && fields.get(required).isEmpty())
            throw new Failure(Status.UNSUPPORTED, Reason.FORM);
        fields.putIfAbsent("__EVENTTARGET", ""); fields.putIfAbsent("__EVENTARGUMENT", "");
        fields.put("IsFromNewSite", "1");
        // The official LoginForm.js sets this immediately before its cross-page Default.aspx POST.
        fields.put("JavaScriptTest", "1");
        return fields;
    }
    private static Map<String, String> attributes(String raw) throws Failure {
        Map<String, String> result = new LinkedHashMap<>(); int end = 0;
        Matcher matcher = ATTRIBUTE.matcher(raw);
        while (matcher.find()) {
            if (!raw.substring(end, matcher.start()).trim().isEmpty()) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3) != null ? matcher.group(3)
                : matcher.group(4) != null ? matcher.group(4) : "";
            boolean used = Arrays.asList("name", "value", "type", "id", "method", "action").contains(key);
            if (result.put(key, used ? entities(value) : value) != null) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
            end = matcher.end();
        }
        if (!raw.substring(end).trim().replace("/", "").isEmpty()) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
        return result;
    }
    private static String entities(String value) throws Failure {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if (next != '&') { out.append(next); continue; }
            int end = value.indexOf(';', i + 1);
            if (end < 0 || end - i > 12) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
            String entity = value.substring(i + 1, end); int point;
            switch (entity) {
                case "amp": point = '&'; break; case "quot": point = '"'; break; case "apos": point = '\''; break;
                case "lt": point = '<'; break; case "gt": point = '>'; break;
                default:
                    try { point = entity.startsWith("#x") || entity.startsWith("#X") ? Integer.parseInt(entity.substring(2), 16)
                        : entity.startsWith("#") ? Integer.parseInt(entity.substring(1)) : -1; }
                    catch (NumberFormatException ignored) { point = -1; }
            }
            if (!Character.isValidCodePoint(point) || point == 0 || point >= 0xd800 && point <= 0xdfff) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
            out.appendCodePoint(point); i = end;
        }
        return out.toString();
    }
    private static boolean challenge(String source) {
        String lower = markup(source).toLowerCase(Locale.ROOT);
        return lower.contains("g-recaptcha-response") || lower.contains("h-captcha-response")
            || lower.contains("autocomplete=\"one-time-code\"") || lower.contains("autocomplete='one-time-code'");
    }
    private static String markup(String source) {
        return source.replaceAll("(?is)<!--.*?-->", "").replaceAll("(?is)<script\\b[^>]*>.*?</script\\s*>", "");
    }
    private static byte[] encode(Map<String, String> fields, String username, char[] password) throws Failure {
        SecretBytes out = new SecretBytes();
        try {
            for (Map.Entry<String, String> field : fields.entrySet()) pair(out, field.getKey(), field.getValue().toCharArray());
            pair(out, USER_FIELD, username.toCharArray()); pair(out, PASSWORD_FIELD, password.clone());
            if (out.size() > MAX_BODY_BYTES) throw new Failure(Status.UNSUPPORTED, Reason.FORM);
            return out.toByteArray();
        } finally { out.erase(); }
    }
    private static void pair(SecretBytes out, String key, char[] value) throws Failure {
        if (out.size() > 0) out.write('&');
        component(out, key.toCharArray()); out.write('='); component(out, value);
    }
    private static void component(SecretBytes out, char[] chars) throws Failure {
        ByteBuffer bytes = null;
        try {
            bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(chars));
            while (bytes.hasRemaining()) {
                int b = bytes.get() & 255;
                if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || b == '-' || b == '_' || b == '.' || b == '*') out.write(b);
                else if (b == ' ') out.write('+');
                else { out.write('%'); out.write("0123456789ABCDEF".charAt(b >>> 4)); out.write("0123456789ABCDEF".charAt(b & 15)); }
            }
        } catch (CharacterCodingException ignored) { throw new Failure(Status.REJECTED, Reason.INPUT); }
        finally { Arrays.fill(chars, '\0'); if (bytes != null && bytes.hasArray()) Arrays.fill(bytes.array(), (byte) 0); }
    }
    private static final class SecretBytes extends ByteArrayOutputStream { void erase() { Arrays.fill(buf, (byte) 0); reset(); } }
    private void checkDeadline(long deadline) throws Failure {
        long now = clock.elapsed(); if (now < 0 || now >= deadline || Thread.currentThread().isInterrupted()) throw new Failure(Status.UNAVAILABLE, Reason.TIMEOUT);
    }
    private static boolean header(String value, int bound) {
        if (value == null || value.length() > bound) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) return false;
        return true;
    }
    private static boolean redirect(int code) { return code == 301 || code == 302 || code == 303 || code == 307 || code == 308; }
    private static boolean loginPath(String path) { return LOGIN_PATH.equals(path) || "/login.aspx".equals(path) || POST_PATH.equals(path); }
    private static String target(String basePath, String location) throws Failure {
        if (!header(location, 2048) || location.isEmpty()) throw new Failure(Status.UNSUPPORTED, Reason.REDIRECT);
        try {
            URI uri = URI.create(ORIGIN + basePath).resolve(location);
            if (!"https".equals(uri.getScheme()) || !"www.alarm.com".equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null
                    || !uri.getRawPath().equals(uri.getPath())) throw new Failure(Status.UNSUPPORTED, Reason.REDIRECT);
            return uri.getPath();
        } catch (IllegalArgumentException ignored) { throw new Failure(Status.UNSUPPORTED, Reason.REDIRECT); }
    }
    static final class HttpTransport implements Transport {
        private final Clock clock; private final ConnectionFactory connections;
        HttpTransport(Clock clock) { this(clock, url -> (HttpURLConnection) url.openConnection()); }
        HttpTransport(Clock clock, ConnectionFactory connections) { this.clock = clock; this.connections = connections; }
        @Override public Response execute(Request request, long deadline) throws IOException {
            URL url = new URL(request.url);
            if (!"https".equals(url.getProtocol()) || !"www.alarm.com".equals(url.getHost()) || url.getPort() != -1
                    || url.getQuery() != null || url.getRef() != null || url.getUserInfo() != null
                    || !("GET".equals(request.method) && loginPath(url.getPath()) && request.body == null
                        || "POST".equals(request.method) && POST_PATH.equals(url.getPath()) && request.body != null))
                throw new IOException("Unsupported login request");
            HttpURLConnection connection = connections.open(url);
            try {
                connection.setRequestMethod(request.method); connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                connection.setConnectTimeout(timeout(deadline)); connection.setReadTimeout(timeout(deadline));
                for (Map.Entry<String, String> field : request.headers.entrySet()) connection.setRequestProperty(field.getKey(), field.getValue());
                if (request.body != null) {
                    connection.setDoOutput(true); connection.setFixedLengthStreamingMode(request.body.length);
                    timeout(deadline);
                    try (OutputStream output = connection.getOutputStream()) {
                        // Stream acquisition can connect/TLS-handshake. Recheck before credentials
                        // leave the process if that work exhausted the deadline or was cancelled.
                        timeout(deadline);
                        output.write(request.body);
                    }
                } else connection.setDoOutput(false);
                connection.setReadTimeout(timeout(deadline));
                int code = connection.getResponseCode();
                List<String> cookies = new ArrayList<>();
                for (Map.Entry<String, List<String>> field : connection.getHeaderFields().entrySet())
                    if (field.getKey() != null && "Set-Cookie".equalsIgnoreCase(field.getKey()) && field.getValue() != null) cookies.addAll(field.getValue());
                byte[] body = new byte[0];
                if (code == 200) {
                    if (connection.getContentLengthLong() > MAX_BODY_BYTES) throw new LimitedIOException(Reason.RESPONSE_SIZE);
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int count;
                        while (true) {
                            connection.setReadTimeout(timeout(deadline)); count = input.read(buffer); if (count < 0) break;
                            if (bytes.size() > MAX_BODY_BYTES - count) throw new LimitedIOException(Reason.RESPONSE_SIZE);
                            bytes.write(buffer, 0, count);
                        }
                        body = bytes.toByteArray();
                    }
                }
                return new Response(code, connection.getContentType(), body, cookies, connection.getHeaderField("Location"));
            } finally { connection.disconnect(); }
        }
        private int timeout(long deadline) throws SocketTimeoutException {
            long remaining = deadline - clock.elapsed();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new SocketTimeoutException("Login deadline");
            return (int) Math.min(3000, remaining);
        }
    }
}
