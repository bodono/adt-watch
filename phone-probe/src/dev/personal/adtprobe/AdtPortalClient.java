package dev.personal.adtprobe;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Read-only use of Alarm.com's unofficial customer-portal JSON API. Never sends an alarm command. */
final class AdtPortalClient {
    static final String ORIGIN = "https://www.alarm.com";
    static final int MAX_BODY_BYTES = 256 * 1024;
    static final long MAX_QUERY_MS = 10_000;

    interface Session {
        String cookies();
        String userAgent();
        void storeCookie(String setCookie);
        default void persist() { }
    }
    enum Status { READY, BUSY, LOGIN_REQUIRED, VERIFY_LOGIN, UNAVAILABLE, UNSUPPORTED, AMBIGUOUS }

    static final class Result {
        final Status status;
        final AlarmStateProtocol.State state;
        final String systemId, partitionId, systemLabel, partitionLabel;
        final long elapsedMillis;
        final int actualStateCode, desiredStateCode;
        final Boolean loading;
        Result(Status status, AlarmStateProtocol.State state, String systemId, String partitionId,
                String systemLabel, String partitionLabel, long elapsedMillis) {
            this(status, state, systemId, partitionId, systemLabel, partitionLabel, elapsedMillis, -1, -1, null);
        }
        Result(Status status, AlarmStateProtocol.State state, String systemId, String partitionId,
                String systemLabel, String partitionLabel, long elapsedMillis, int actualStateCode, int desiredStateCode,
                Boolean loading) {
            this.status = status; this.state = state; this.systemId = systemId; this.partitionId = partitionId;
            this.systemLabel = systemLabel; this.partitionLabel = partitionLabel; this.elapsedMillis = elapsedMillis;
            this.actualStateCode = actualStateCode; this.desiredStateCode = desiredStateCode; this.loading = loading;
        }
        @Override public String toString() { return "ADT portal result: " + status + " (" + elapsedMillis + " ms)"; }
    }

    /** Inert test seam. Requests have no configurable method, origin, body or redirect handling. */
    interface Transport { Response get(Request request, long deadlineElapsed) throws IOException; }
    interface Clock { long elapsed(); }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    static final class Request {
        final String url;
        final Map<String, String> headers;
        private Request(String path, Map<String, String> headers) throws Failure {
            if (!validPath(path)) throw new Failure(Status.UNSUPPORTED);
            url = ORIGIN + path;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }
        @Override public String toString() { return "GET ADT portal (session headers redacted)"; }
    }
    static final class Response {
        final int code;
        final String contentType;
        final byte[] body;
        final List<String> setCookies;
        Response(int code, String contentType, byte[] body, List<String> setCookies) {
            this.code = code; this.contentType = contentType; this.body = body;
            this.setCookies = setCookies == null ? Collections.emptyList() : setCookies;
        }
        @Override public String toString() { return "ADT portal HTTP " + code + " (content redacted)"; }
    }
    private static final class Failure extends Exception {
        final Status status;
        Failure(Status status) { super(status.name()); this.status = status; }
    }

    private final Session session;
    private final Transport transport;
    private final Clock clock;

    AdtPortalClient(Session session) {
        this.session = session; clock = SystemClock::elapsedRealtime; transport = new HttpTransport(clock);
    }
    AdtPortalClient(Session session, Transport transport, Clock clock) {
        this.session = session; this.transport = transport; this.clock = clock;
    }

    /** Must be called on a worker thread. All failures discard partial state; there is no cached fallback. */
    Result query(long deadlineElapsed) {
        long started = clock.elapsed();
        long deadline = Math.min(deadlineElapsed, started > Long.MAX_VALUE - MAX_QUERY_MS
                ? Long.MAX_VALUE : started + MAX_QUERY_MS);
        try {
            if (started < 0 || session == null || deadline <= started) throw new Failure(Status.UNAVAILABLE);
            JSONObject identities = fetch("/web/api/identities", deadline);
            JSONArray accounts = array(identities, "data");
            if (accounts.length() > 1) throw new Failure(Status.AMBIGUOUS);
            if (accounts.length() != 1) throw new Failure(Status.UNSUPPORTED);
            JSONObject identity = object(accounts.get(0));
            requireType(identity, "identity");
            resourceId(identity.get("id"));
            JSONObject selected = object(object(object(identity, "relationships"), "selectedSystem"), "data");
            requireType(selected, "systems/system");
            String systemId = resourceId(selected.get("id"));

            JSONObject system = object(fetch("/web/api/systems/systems/" + systemId, deadline), "data");
            requireType(system, "systems/system");
            if (!systemId.equals(resourceId(system.get("id")))) throw new Failure(Status.AMBIGUOUS);
            String systemLabel = label(object(system, "attributes"));
            JSONObject partitionLinks = object(object(system, "relationships"), "partitions");
            JSONArray partitions = array(partitionLinks, "data");
            if (partitions.length() > 1) throw new Failure(Status.AMBIGUOUS);
            if (partitions.length() != 1) throw new Failure(Status.UNSUPPORTED);
            JSONObject partitionLink = object(partitions.get(0));
            requireType(partitionLink, "devices/partition");
            String partitionId = partitionId(partitionLink.get("id"));

            JSONObject partition = object(fetch("/web/api/devices/partitions/" + partitionId, deadline), "data");
            requireType(partition, "devices/partition");
            if (!partitionId.equals(partitionId(partition.get("id")))) throw new Failure(Status.AMBIGUOUS);
            // Some portal versions also include the explicit owning-system relationship.
            if (partition.has("relationships")) {
                JSONObject relationships = object(partition, "relationships");
                if (relationships.has("system")) {
                    JSONObject owner = object(object(relationships, "system"), "data");
                    requireType(owner, "systems/system");
                    if (!systemId.equals(resourceId(owner.get("id")))) throw new Failure(Status.AMBIGUOUS);
                }
            }
            JSONObject attributes = object(partition, "attributes");
            if (!booleanValue(attributes.get("hasState"))) throw new Failure(Status.UNSUPPORTED);
            String partitionLabel = label(attributes);
            int actual = integer(attributes.get("state")), desired = integer(attributes.get("desiredState"));
            if (actual < 1 || actual > 3 || desired < 0 || desired > 4) throw new Failure(Status.UNSUPPORTED);
            Boolean loading = attributes.has("loading") ? booleanValue(attributes.get("loading")) : null;
            checkDeadline(deadline);
            AlarmStateProtocol.State state = actual == 1 ? AlarmStateProtocol.State.DISARMED
                : actual == 2 ? AlarmStateProtocol.State.ARMED_STAY : AlarmStateProtocol.State.ARMED_AWAY;
            // A different desired state can outlive a failed command; only explicit loading means busy.
            Status status = Boolean.TRUE.equals(loading) ? Status.BUSY : Status.READY;
            return new Result(status, state, systemId, partitionId, systemLabel, partitionLabel, elapsed(started), actual, desired, loading);
        } catch (Failure failure) {
            return failed(failure.status, started);
        } catch (IOException | JSONException | RuntimeException ignored) {
            // Response bodies, server errors, headers and exception text never reach logs or the caller.
            return failed(ignored instanceof IOException ? Status.UNAVAILABLE : Status.UNSUPPORTED, started);
        } finally {
            // Worker-only disk flush preserves rotated login cookies across process death.
            if (session != null) try { session.persist(); } catch (RuntimeException ignored) { }
        }
    }

    private JSONObject fetch(String path, long deadline) throws Failure, IOException, JSONException {
        checkDeadline(deadline);
        String cookies = session.cookies(), userAgent = session.userAgent();
        if (!headerValue(cookies, 16_384) || cookies.isEmpty()) throw new Failure(Status.LOGIN_REQUIRED);
        String ajaxKey = null;
        for (String cookie : cookies.split(";")) {
            int equals = cookie.indexOf('=');
            if (equals >= 0 && "afg".equals(cookie.substring(0, equals).trim())) {
                if (ajaxKey != null) throw new Failure(Status.VERIFY_LOGIN);
                ajaxKey = cookie.substring(equals + 1).trim();
            }
        }
        if (!headerValue(ajaxKey, 2048) || ajaxKey.isEmpty()) throw new Failure(Status.LOGIN_REQUIRED);
        if (!headerValue(userAgent, 1024) || userAgent.isEmpty()) throw new Failure(Status.UNSUPPORTED);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.api+json"); headers.put("Cookie", cookies);
        headers.put("ajaxrequestuniquekey", ajaxKey); headers.put("User-Agent", userAgent);
        headers.put("Referer", ORIGIN + "/web/system/home");
        headers.put("Cache-Control", "no-cache, no-store"); headers.put("Pragma", "no-cache");
        Response response = transport.get(new Request(path, headers), deadline);
        checkDeadline(deadline);
        if (response == null || response.setCookies.size() > 16) throw new Failure(Status.UNAVAILABLE);
        for (String cookie : response.setCookies) {
            if (!headerValue(cookie, 8192)) throw new Failure(Status.UNAVAILABLE);
            session.storeCookie(cookie);
        }
        if (response.code == 401 || response.code >= 300 && response.code < 400) throw new Failure(Status.LOGIN_REQUIRED);
        if (response.code == 403 || response.code == 423) throw new Failure(Status.VERIFY_LOGIN);
        if (response.code != 200) throw new Failure(Status.UNAVAILABLE);
        String type = response.contentType == null ? "" : response.contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if ("text/html".equals(type)) throw new Failure(Status.LOGIN_REQUIRED);
        if (!"application/json".equals(type) && !"application/vnd.api+json".equals(type)) throw new Failure(Status.UNSUPPORTED);
        if (response.body == null || response.body.length == 0 || response.body.length > MAX_BODY_BYTES)
            throw new Failure(Status.UNAVAILABLE);
        String body;
        try {
            body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body)).toString();
        } catch (CharacterCodingException error) { throw new Failure(Status.UNSUPPORTED); }
        JSONTokener reader = new JSONTokener(body);
        JSONObject document = object(reader.nextValue());
        if (reader.nextClean() != 0 || document.has("errors")) throw new Failure(Status.UNSUPPORTED);
        return document;
    }

    private Result failed(Status status, long started) {
        return new Result(status, AlarmStateProtocol.State.UNKNOWN, "", "", "", "", elapsed(started));
    }
    private long elapsed(long started) { return Math.max(0, clock.elapsed() - started); }
    private void checkDeadline(long deadline) throws Failure {
        long now = clock.elapsed();
        if (now < 0 || now >= deadline) throw new Failure(Status.UNAVAILABLE);
    }
    private static boolean validPath(String path) {
        return "/web/api/identities".equals(path)
            || path.matches("/web/api/systems/systems/[A-Za-z0-9_-]{1,80}")
            || path.matches("/web/api/devices/partitions/[A-Za-z0-9_-]{1,80}");
    }
    private static JSONObject object(Object value) throws Failure {
        if (!(value instanceof JSONObject)) throw new Failure(Status.UNSUPPORTED);
        return (JSONObject) value;
    }
    private static JSONObject object(JSONObject parent, String key) throws Failure, JSONException { return object(parent.get(key)); }
    private static JSONArray array(JSONObject parent, String key) throws Failure, JSONException {
        Object value = parent.get(key);
        if (!(value instanceof JSONArray)) throw new Failure(Status.UNSUPPORTED);
        return (JSONArray) value;
    }
    private static void requireType(JSONObject resource, String type) throws Failure, JSONException {
        if (!type.equals(resource.get("type"))) throw new Failure(Status.UNSUPPORTED);
    }
    private static String resourceId(Object value) throws Failure {
        if (!(value instanceof String || value instanceof Integer || value instanceof Long)) throw new Failure(Status.UNSUPPORTED);
        String id = value.toString();
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new Failure(Status.UNSUPPORTED);
        return id;
    }
    private static String partitionId(Object value) throws Failure {
        return resourceId(value);
    }
    private static int integer(Object value) throws Failure {
        if (!(value instanceof Integer || value instanceof Long)) throw new Failure(Status.UNSUPPORTED);
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new Failure(Status.UNSUPPORTED);
        return (int) number;
    }
    private static boolean booleanValue(Object value) throws Failure {
        if (!(value instanceof Boolean)) throw new Failure(Status.UNSUPPORTED);
        return (Boolean) value;
    }
    private static String label(JSONObject attributes) throws Failure, JSONException {
        Object value = attributes.get("description");
        if (!(value instanceof String) || ((String) value).length() > 200) throw new Failure(Status.UNSUPPORTED);
        String label = ((String) value).trim();
        if (label.isEmpty()) throw new Failure(Status.UNSUPPORTED);
        for (int i = 0; i < label.length(); i++) if (Character.isISOControl(label.charAt(i))) throw new Failure(Status.UNSUPPORTED);
        return label;
    }
    private static boolean headerValue(String value, int maxLength) {
        if (value == null || value.length() > maxLength) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) > 126) return false;
        return true;
    }

    static final class HttpTransport implements Transport {
        private final Clock clock;
        private final ConnectionFactory connections;
        HttpTransport(Clock clock) { this(clock, url -> (HttpURLConnection) url.openConnection()); }
        HttpTransport(Clock clock, ConnectionFactory connections) { this.clock = clock; this.connections = connections; }
        @Override public Response get(Request request, long deadlineElapsed) throws IOException {
            URL url = new URL(request.url);
            if (!"https".equals(url.getProtocol()) || !"www.alarm.com".equals(url.getHost()) || url.getPort() != -1
                    || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null || !validPath(url.getPath()))
                throw new IOException("Invalid portal path");
            HttpURLConnection connection = connections.open(url);
            try {
                connection.setRequestMethod("GET"); connection.setInstanceFollowRedirects(false);
                connection.setDoOutput(false); connection.setUseCaches(false);
                connection.setConnectTimeout(timeout(deadlineElapsed)); connection.setReadTimeout(timeout(deadlineElapsed));
                for (Map.Entry<String, String> header : request.headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                int code = connection.getResponseCode();
                List<String> cookies = new ArrayList<>();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet())
                    if (header.getKey() != null && "Set-Cookie".equalsIgnoreCase(header.getKey()) && header.getValue() != null)
                        cookies.addAll(header.getValue());
                byte[] body = new byte[0];
                if (code == 200) {
                    if (connection.getContentLengthLong() > MAX_BODY_BYTES) throw new IOException("Portal response too large");
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] chunk = new byte[4096];
                        while (true) {
                            connection.setReadTimeout(timeout(deadlineElapsed));
                            int count = input.read(chunk);
                            if (count < 0) break;
                            if (bytes.size() > MAX_BODY_BYTES - count) throw new IOException("Portal response too large");
                            bytes.write(chunk, 0, count);
                        }
                        body = bytes.toByteArray();
                    }
                }
                return new Response(code, connection.getContentType(), body, cookies);
            } finally { connection.disconnect(); }
        }
        private int timeout(long deadline) throws IOException {
            long remaining = deadline - clock.elapsed();
            if (remaining <= 0) throw new IOException("Portal request expired");
            return (int) Math.min(3000, remaining);
        }
    }
}
