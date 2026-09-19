package dev.personal.adtprobe;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.ByteBuffer;
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
import javax.net.ssl.SSLException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Read-only use of Alarm.com's unofficial customer-portal JSON API. Never sends an alarm command. */
final class AdtPortalClient {
    static final String ORIGIN = "https://www.alarm.com";
    /** Origins a portal session may live on: Alarm.com itself or ADT UK's branded portal host. */
    static final List<String> ORIGINS = Collections.unmodifiableList(Arrays.asList(ORIGIN, "https://smartservices.adt.co.uk"));
    static final int MAX_BODY_BYTES = 256 * 1024;
    // Identity responses also embed portal configuration and included relationship resources.
    static final int MAX_IDENTITY_BODY_BYTES = 4 * 1024 * 1024;
    static final long MAX_QUERY_MS = 10_000;

    interface Session {
        String cookies();
        default String cookies(String requestUrl) { return cookies(); }
        String userAgent();
        void storeCookie(String setCookie);
        default void storeCookie(String responseUrl, String setCookie) { storeCookie(setCookie); }
        default void persist() { }
        /** The https origin whose cookie jar holds the sign-in; API paths are appended to it. */
        default String origin() { return ORIGIN; }
    }
    /**
     * A session wrapper throws this when its authentication is being replaced or its read was
     * cancelled. That says nothing about ADT's response, so clients report it as a transient
     * UNAVAILABLE that a later read retries, never as an unsupported response.
     */
    static final class SessionUnavailable extends IllegalStateException {
        SessionUnavailable(String message) { super(message); }
    }
    enum Status { READY, BUSY, LOGIN_REQUIRED, VERIFY_LOGIN, UNAVAILABLE, UNSUPPORTED, AMBIGUOUS }
    enum Stage { SESSION, IDENTITIES, SYSTEM, PARTITION }
    enum Reason { NONE, HTTP, TIMEOUT, DNS, TLS, IO, SCHEMA, RESPONSE_SIZE, COOKIE_FORMAT, SESSION, EMPTY_RESPONSE, CONTENT_TYPE }

    static final class Result {
        final Status status;
        final AlarmStateProtocol.State state;
        final String systemId, partitionId, systemLabel, partitionLabel;
        final long elapsedMillis;
        final int actualStateCode, desiredStateCode;
        final Boolean loading;
        final Stage stage;
        final Reason reason;
        final int httpStatus;
        Result(Status status, AlarmStateProtocol.State state, String systemId, String partitionId,
                String systemLabel, String partitionLabel, long elapsedMillis) {
            this(status, state, systemId, partitionId, systemLabel, partitionLabel, elapsedMillis, -1, -1, null);
        }
        Result(Status status, AlarmStateProtocol.State state, String systemId, String partitionId,
                String systemLabel, String partitionLabel, long elapsedMillis, int actualStateCode, int desiredStateCode,
                Boolean loading) {
            this(status, state, systemId, partitionId, systemLabel, partitionLabel, elapsedMillis,
                actualStateCode, desiredStateCode, loading, Stage.SESSION, Reason.NONE, 0);
        }
        private Result(Status status, AlarmStateProtocol.State state, String systemId, String partitionId,
                String systemLabel, String partitionLabel, long elapsedMillis, int actualStateCode, int desiredStateCode,
                Boolean loading, Stage stage, Reason reason, int httpStatus) {
            this.status = status; this.state = state; this.systemId = systemId; this.partitionId = partitionId;
            this.systemLabel = systemLabel; this.partitionLabel = partitionLabel; this.elapsedMillis = elapsedMillis;
            this.actualStateCode = actualStateCode; this.desiredStateCode = desiredStateCode; this.loading = loading;
            this.stage = stage; this.reason = reason; this.httpStatus = boundedHttp(httpStatus);
        }
        String diagnosticCode() { return stage.name() + "/" + reason.name() + (httpStatus == 0 ? "" : "/" + httpStatus); }
        @Override public String toString() { return "ADT portal result: " + status + " (" + elapsedMillis + " ms)"; }
    }

    /** Inert test seam. Requests have no configurable method, origin, body or redirect handling. */
    interface Transport { Response get(Request request, long deadlineElapsed) throws IOException; }
    interface Clock { long elapsed(); }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    static final class Request {
        final String url;
        final int maxBodyBytes;
        final Map<String, String> headers;
        private Request(String origin, String path, Map<String, String> headers) throws Failure {
            if (!ORIGINS.contains(origin) || !validPath(path)) throw new Failure(Status.UNSUPPORTED);
            url = origin + path;
            maxBodyBytes = bodyLimit(path);
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
        final Reason reason;
        Failure(Status status) { this(status, Reason.SCHEMA); }
        Failure(Status status, Reason reason) { super(status.name()); this.status = status; this.reason = reason; }
    }
    private static final class Diagnostic {
        Stage stage = Stage.SESSION;
        int http;
    }
    private static final class PortalIOException extends IOException {
        final Reason reason;
        final int http;
        PortalIOException(Reason reason, int http) { super(reason.name()); this.reason = reason; this.http = boundedHttp(http); }
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
        return query(null, null, false, deadlineElapsed);
    }

    /** Routine reads prove membership in the saved system without downloading portal identity configuration. */
    Result queryBound(String expectedSystemId, String expectedPartitionId, long deadlineElapsed) {
        return query(expectedSystemId, expectedPartitionId, true, deadlineElapsed);
    }

    private Result query(String expectedSystemId, String expectedPartitionId, boolean bound, long deadlineElapsed) {
        long started = clock.elapsed();
        long deadline = Math.min(deadlineElapsed, started > Long.MAX_VALUE - MAX_QUERY_MS
                ? Long.MAX_VALUE : started + MAX_QUERY_MS);
        Diagnostic diagnostic = new Diagnostic();
        try {
            if (session == null) throw new Failure(Status.UNAVAILABLE, Reason.SESSION);
            if (started < 0 || deadline <= started) throw new Failure(Status.UNAVAILABLE, Reason.TIMEOUT);
            String systemId;
            if (bound) {
                systemId = resourceId(expectedSystemId);
                resourceId(expectedPartitionId);
            } else {
                JSONObject identities = fetch("/web/api/identities", deadline, Stage.IDENTITIES, diagnostic);
                JSONArray accounts = array(identities, "data");
                if (accounts.length() > 1) throw new Failure(Status.AMBIGUOUS);
                if (accounts.length() != 1) throw new Failure(Status.UNSUPPORTED);
                JSONObject identity = object(accounts.get(0));
                requireType(identity, "identity");
                resourceId(identity.get("id"));
                JSONObject selected = object(object(object(identity, "relationships"), "selectedSystem"), "data");
                requireType(selected, "systems/system");
                systemId = resourceId(selected.get("id"));
            }

            JSONObject system = fetchSystem(systemId, deadline, diagnostic);
            String systemLabel = label(object(system, "attributes"));
            String partitionId = solePartitionId(system);
            if (bound && !partitionId.equals(expectedPartitionId)) throw new Failure(Status.AMBIGUOUS);

            JSONObject partition = object(fetch("/web/api/devices/partitions/" + partitionId, deadline, Stage.PARTITION, diagnostic), "data");
            return partitionResult(partition, systemId, partitionId, systemLabel, started, deadline, diagnostic);
        } catch (Failure failure) {
            return failed(failure.status, started, diagnostic, failure.reason);
        } catch (IOException failure) {
            Reason reason = ioReason(failure);
            if (failure instanceof PortalIOException) {
                reason = ((PortalIOException) failure).reason;
                diagnostic.http = ((PortalIOException) failure).http;
            }
            return failed(Status.UNAVAILABLE, started, diagnostic, reason);
        } catch (SessionUnavailable ignored) {
            return failed(Status.UNAVAILABLE, started, diagnostic, Reason.SESSION);
        } catch (JSONException | RuntimeException ignored) {
            // Response bodies, server errors, headers and exception text never reach logs or the caller.
            return failed(Status.UNSUPPORTED, started, diagnostic, Reason.SCHEMA);
        } finally {
            // Worker-only disk flush preserves rotated login cookies across process death.
            if (session != null) try { session.persist(); } catch (RuntimeException ignored) { }
        }
    }

    /**
     * Routine read of the partition chosen at setup. queryBound proves membership in the saved
     * system with two reads; repeating that on every status check doubled the round trips the
     * watch waited for. A partition response that names the saved system as its owner proves the
     * binding by itself. One without that relationship is followed by the same system read
     * queryBound makes, so the saved system id is never merely assumed for a response.
     */
    Result status(String expectedSystemId, String expectedPartitionId, long deadlineElapsed) {
        long started = clock.elapsed();
        long deadline = Math.min(deadlineElapsed, started > Long.MAX_VALUE - MAX_QUERY_MS
                ? Long.MAX_VALUE : started + MAX_QUERY_MS);
        Diagnostic diagnostic = new Diagnostic();
        try {
            if (session == null) throw new Failure(Status.UNAVAILABLE, Reason.SESSION);
            if (started < 0 || deadline <= started) throw new Failure(Status.UNAVAILABLE, Reason.TIMEOUT);
            String systemId = resourceId(expectedSystemId), partitionId = resourceId(expectedPartitionId);
            JSONObject partition = object(fetch("/web/api/devices/partitions/" + partitionId, deadline, Stage.PARTITION, diagnostic), "data");
            requireType(partition, "devices/partition");
            if (!partitionId.equals(partitionId(partition.get("id")))) throw new Failure(Status.AMBIGUOUS);
            String systemLabel = "";
            if (!ownerProven(partition, systemId)) {
                JSONObject system = fetchSystem(systemId, deadline, diagnostic);
                if (!partitionId.equals(solePartitionId(system))) throw new Failure(Status.AMBIGUOUS);
                systemLabel = label(object(system, "attributes"));
            }
            return partitionResult(partition, systemId, partitionId, systemLabel, started, deadline, diagnostic);
        } catch (Failure failure) {
            return failed(failure.status, started, diagnostic, failure.reason);
        } catch (IOException failure) {
            Reason reason = ioReason(failure);
            if (failure instanceof PortalIOException) {
                reason = ((PortalIOException) failure).reason;
                diagnostic.http = ((PortalIOException) failure).http;
            }
            return failed(Status.UNAVAILABLE, started, diagnostic, reason);
        } catch (SessionUnavailable ignored) {
            return failed(Status.UNAVAILABLE, started, diagnostic, Reason.SESSION);
        } catch (JSONException | RuntimeException ignored) {
            return failed(Status.UNSUPPORTED, started, diagnostic, Reason.SCHEMA);
        } finally {
            if (session != null) try { session.persist(); } catch (RuntimeException ignored) { }
        }
    }

    private Result partitionResult(JSONObject partition, String systemId, String partitionId, String systemLabel,
            long started, long deadline, Diagnostic diagnostic) throws Failure, JSONException {
        requireType(partition, "devices/partition");
        if (!partitionId.equals(partitionId(partition.get("id")))) throw new Failure(Status.AMBIGUOUS);
        ownerProven(partition, systemId);
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
        return new Result(status, state, systemId, partitionId, systemLabel, partitionLabel, elapsed(started),
            actual, desired, loading, diagnostic.stage, Reason.NONE, diagnostic.http);
    }

    /** Whether the partition response names the expected owning system; a different owner is refused. */
    private static boolean ownerProven(JSONObject partition, String systemId) throws Failure, JSONException {
        if (!partition.has("relationships")) return false;
        JSONObject relationships = object(partition, "relationships");
        if (!relationships.has("system")) return false;
        JSONObject owner = object(object(relationships, "system"), "data");
        requireType(owner, "systems/system");
        if (!systemId.equals(resourceId(owner.get("id")))) throw new Failure(Status.AMBIGUOUS);
        return true;
    }

    private JSONObject fetchSystem(String systemId, long deadline, Diagnostic diagnostic) throws Failure, IOException, JSONException {
        JSONObject system = object(fetch("/web/api/systems/systems/" + systemId, deadline, Stage.SYSTEM, diagnostic), "data");
        requireType(system, "systems/system");
        if (!systemId.equals(resourceId(system.get("id")))) throw new Failure(Status.AMBIGUOUS);
        return system;
    }

    /** The system must list exactly one partition; that is the only partition this client supports. */
    private static String solePartitionId(JSONObject system) throws Failure, JSONException {
        JSONArray partitions = array(object(object(system, "relationships"), "partitions"), "data");
        if (partitions.length() > 1) throw new Failure(Status.AMBIGUOUS);
        if (partitions.length() != 1) throw new Failure(Status.UNSUPPORTED);
        JSONObject partitionLink = object(partitions.get(0));
        requireType(partitionLink, "devices/partition");
        return partitionId(partitionLink.get("id"));
    }

    private JSONObject fetch(String path, long deadline, Stage stage, Diagnostic diagnostic) throws Failure, IOException, JSONException {
        diagnostic.stage = Stage.SESSION; diagnostic.http = 0;
        checkDeadline(deadline);
        String origin = session.origin();
        String cookies = session.cookies(origin + path), userAgent = session.userAgent();
        if (!headerValue(cookies, 16_384)) throw new Failure(Status.LOGIN_REQUIRED, cookies == null ? Reason.SESSION : Reason.COOKIE_FORMAT);
        if (cookies.isEmpty()) throw new Failure(Status.LOGIN_REQUIRED, Reason.SESSION);
        String ajaxKey = null;
        for (String cookie : cookies.split(";")) {
            int equals = cookie.indexOf('=');
            if (equals >= 0 && "afg".equals(cookie.substring(0, equals).trim())) {
                if (ajaxKey != null) throw new Failure(Status.VERIFY_LOGIN, Reason.COOKIE_FORMAT);
                ajaxKey = cookie.substring(equals + 1).trim();
            }
        }
        if (!headerValue(ajaxKey, 2048) || ajaxKey.isEmpty()) throw new Failure(Status.LOGIN_REQUIRED, Reason.SESSION);
        if (!headerValue(userAgent, 1024) || userAgent.isEmpty()) throw new Failure(Status.UNSUPPORTED, Reason.SESSION);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.api+json"); headers.put("Cookie", cookies);
        headers.put("ajaxrequestuniquekey", ajaxKey); headers.put("User-Agent", userAgent);
        headers.put("Referer", origin + "/web/system/home");
        headers.put("Cache-Control", "no-cache, no-store"); headers.put("Pragma", "no-cache");
        diagnostic.stage = stage;
        Response response = transport.get(new Request(origin, path, headers), deadline);
        if (response != null) diagnostic.http = boundedHttp(response.code);
        checkDeadline(deadline);
        if (response == null) throw new Failure(Status.UNAVAILABLE, Reason.EMPTY_RESPONSE);
        if (response.setCookies.size() > 16) throw new Failure(Status.UNAVAILABLE, Reason.COOKIE_FORMAT);
        for (String cookie : response.setCookies) {
            if (!headerValue(cookie, 8192)) throw new Failure(Status.UNAVAILABLE, Reason.COOKIE_FORMAT);
            session.storeCookie(origin + path, cookie);
        }
        if (response.code == 401 || response.code >= 300 && response.code < 400) throw new Failure(Status.LOGIN_REQUIRED, Reason.HTTP);
        if (response.code == 403 || response.code == 409 || response.code == 423) throw new Failure(Status.VERIFY_LOGIN, Reason.HTTP);
        if (response.code != 200) throw new Failure(Status.UNAVAILABLE, Reason.HTTP);
        String type = mediaType(response.contentType);
        if ("text/html".equals(type)) throw new Failure(Status.LOGIN_REQUIRED, Reason.CONTENT_TYPE);
        if (!jsonType(type)) throw new Failure(Status.UNSUPPORTED, Reason.CONTENT_TYPE);
        if (response.body == null || response.body.length == 0) throw new Failure(Status.UNAVAILABLE, Reason.EMPTY_RESPONSE);
        if (response.body.length > bodyLimit(path)) throw new Failure(Status.UNAVAILABLE, Reason.RESPONSE_SIZE);
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

    private Result failed(Status status, long started, Diagnostic diagnostic, Reason reason) {
        return new Result(status, AlarmStateProtocol.State.UNKNOWN, "", "", "", "", elapsed(started),
            -1, -1, null, diagnostic.stage, reason, diagnostic.http);
    }
    private static int boundedHttp(int status) { return status >= 100 && status <= 599 ? status : 0; }
    private static int bodyLimit(String path) {
        return "/web/api/identities".equals(path) ? MAX_IDENTITY_BODY_BYTES : MAX_BODY_BYTES;
    }
    private static String mediaType(String type) {
        return type == null ? "" : type.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }
    private static boolean jsonType(String type) {
        return "application/json".equals(type) || "application/vnd.api+json".equals(type);
    }
    private static Reason ioReason(IOException failure) {
        return failure instanceof SocketTimeoutException ? Reason.TIMEOUT
            : failure instanceof UnknownHostException ? Reason.DNS : failure instanceof SSLException ? Reason.TLS : Reason.IO;
    }
    private long elapsed(long started) { return Math.max(0, clock.elapsed() - started); }
    private void checkDeadline(long deadline) throws Failure {
        long now = clock.elapsed();
        if (now < 0 || now >= deadline) throw new Failure(Status.UNAVAILABLE, Reason.TIMEOUT);
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
            if (!"https".equals(url.getProtocol()) || !ORIGINS.contains("https://" + url.getHost()) || url.getPort() != -1
                    || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null || !validPath(url.getPath()))
                throw new IOException("Invalid portal path");
            HttpURLConnection connection = connections.open(url);
            int code = 0;
            try {
                connection.setRequestMethod("GET"); connection.setInstanceFollowRedirects(false);
                connection.setDoOutput(false); connection.setUseCaches(false);
                connection.setConnectTimeout(timeout(deadlineElapsed)); connection.setReadTimeout(timeout(deadlineElapsed));
                for (Map.Entry<String, String> header : request.headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                code = connection.getResponseCode();
                List<String> cookies = new ArrayList<>();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet())
                    if (header.getKey() != null && "Set-Cookie".equalsIgnoreCase(header.getKey()) && header.getValue() != null)
                        cookies.addAll(header.getValue());
                byte[] body = new byte[0];
                String contentType = connection.getContentType();
                // Login/error HTML is classified from its media type, without downloading its body.
                if (code == 200 && jsonType(mediaType(contentType))) {
                    if (connection.getContentLengthLong() > request.maxBodyBytes) throw new PortalIOException(Reason.RESPONSE_SIZE, code);
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] chunk = new byte[4096];
                        while (true) {
                            connection.setReadTimeout(timeout(deadlineElapsed));
                            int count = input.read(chunk);
                            if (count < 0) break;
                            if (bytes.size() > request.maxBodyBytes - count) throw new PortalIOException(Reason.RESPONSE_SIZE, code);
                            bytes.write(chunk, 0, count);
                        }
                        body = bytes.toByteArray();
                    }
                }
                return new Response(code, contentType, body, cookies);
            } catch (PortalIOException failure) { throw failure; }
            catch (IOException failure) { throw new PortalIOException(ioReason(failure), code); }
            finally { connection.disconnect(); }
        }
        private int timeout(long deadline) throws IOException {
            long remaining = deadline - clock.elapsed();
            if (remaining <= 0) throw new SocketTimeoutException("Portal request expired");
            return (int) Math.min(3000, remaining);
        }
    }
}
