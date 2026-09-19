package dev.personal.adtprobe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Inert HTTP and JSON fixtures. No account login, network or native alarm action is exercised. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AdtPortalClientTest {
    private static final String SYSTEM = "inert-system", PARTITION = "unrelated_partition_key";
    private static final String SECRET = "inert-private-session";
    private FakeSession session;
    private FakeClock clock;
    private FakeTransport transport;
    private AdtPortalClient client;

    @Before public void setup() {
        session = new FakeSession(); clock = new FakeClock(); transport = new FakeTransport(clock);
        client = new AdtPortalClient(session, transport, clock);
    }

    @Test public void allSupportedStatesAreActualBackendStateWithExactRelationshipScope() throws Exception {
        AlarmStateProtocol.State[] states = {AlarmStateProtocol.State.DISARMED,
            AlarmStateProtocol.State.ARMED_STAY, AlarmStateProtocol.State.ARMED_AWAY};
        for (int state = 1; state <= 3; state++) {
            enqueue(state, state);
            AdtPortalClient.Result result = client.query(clock.now + 5_000);
            assertEquals(AdtPortalClient.Status.READY, result.status);
            assertEquals(states[state - 1], result.state);
            assertEquals(SYSTEM, result.systemId); assertEquals(PARTITION, result.partitionId);
            assertEquals("Inert Home", result.systemLabel); assertEquals("Inert System", result.partitionLabel);
            assertEquals(300, result.elapsedMillis); assertEquals(state, result.actualStateCode);
            assertEquals(state, result.desiredStateCode); assertNull(result.loading);
        }
        assertEquals(9, transport.requests.size());
        assertEquals(AdtPortalClient.ORIGIN + "/web/api/identities", transport.requests.get(0).url);
        assertEquals(AdtPortalClient.ORIGIN + "/web/api/systems/systems/" + SYSTEM, transport.requests.get(1).url);
        assertEquals(AdtPortalClient.ORIGIN + "/web/api/devices/partitions/" + PARTITION, transport.requests.get(2).url);
    }

    @Test public void desiredStateIsNeverReportedAsActualAndLoadingIsExplicitlyUncertain() throws Exception {
        enqueue(1, 2);
        AdtPortalClient.Result result = client.query(5_000);
        assertEquals("A historical target cannot indefinitely block the actual reported state", AdtPortalClient.Status.READY, result.status);
        assertEquals(AlarmStateProtocol.State.DISARMED, result.state);
        assertEquals(1, result.actualStateCode); assertEquals(2, result.desiredStateCode);
        JSONObject loading = partition(1, 1); loading.getJSONObject("data").getJSONObject("attributes").put("loading", true);
        enqueue(identities(), system(), loading);
        result = client.query(5_000);
        assertEquals(AdtPortalClient.Status.BUSY, result.status); assertEquals(Boolean.TRUE, result.loading);
        enqueue(1, 0);
        assertEquals("No requested target does not replace actual state", AdtPortalClient.Status.READY, client.query(5_000).status);
    }

    @Test public void unsupportedOrCoercedStateAndMissingHasStateNeverBecomeReady() throws Exception {
        for (Object state : new Object[]{0, 4, -1, 8, "1", 1.5, JSONObject.NULL}) {
            JSONObject malformed = partition(1, 1);
            malformed.getJSONObject("data").getJSONObject("attributes").put("state", state);
            enqueue(identities(), system(), malformed);
            assertEquals(AdtPortalClient.Status.UNSUPPORTED, client.query(clock.now + 5_000).status);
        }
        for (Object hasState : new Object[]{false, "true", JSONObject.NULL}) {
            JSONObject malformed = partition(1, 1);
            malformed.getJSONObject("data").getJSONObject("attributes").put("hasState", hasState);
            enqueue(identities(), system(), malformed);
            assertEquals(AdtPortalClient.Status.UNSUPPORTED, client.query(clock.now + 5_000).status);
        }
    }

    @Test public void multipleIdentitiesOrPartitionsAreNotGuessed() throws Exception {
        JSONObject many = identities(); many.getJSONArray("data").put(identity());
        transport.responses.add(json(many));
        assertEquals(AdtPortalClient.Status.AMBIGUOUS, client.query(5_000).status);
        JSONObject multi = system();
        multi.getJSONObject("data").getJSONObject("relationships").getJSONObject("partitions").getJSONArray("data")
            .put(ref("other", "devices/partition"));
        transport.responses.add(json(identities())); transport.responses.add(json(multi));
        assertEquals(AdtPortalClient.Status.AMBIGUOUS, client.query(5_000).status);
        assertEquals(3, transport.requests.size());
    }

    @Test public void mismatchedResourcesAndCrossSystemOwnersAreRejected() throws Exception {
        JSONObject differentSystem = system(); differentSystem.getJSONObject("data").put("id", "another-system");
        transport.responses.add(json(identities())); transport.responses.add(json(differentSystem));
        assertEquals(AdtPortalClient.Status.AMBIGUOUS, client.query(5_000).status);
        JSONObject differentPartition = partition(1, 1); differentPartition.getJSONObject("data").put("id", "another-partition");
        enqueue(identities(), system(), differentPartition);
        assertEquals(AdtPortalClient.Status.AMBIGUOUS, client.query(5_000).status);
        JSONObject otherOwner = partition(1, 1);
        otherOwner.getJSONObject("data").put("relationships", new JSONObject().put("system",
            new JSONObject().put("data", ref("another-system", "systems/system"))));
        enqueue(identities(), system(), otherOwner);
        assertEquals(AdtPortalClient.Status.AMBIGUOUS, client.query(5_000).status);
    }

    @Test public void maliciousIdentifiersCannotCreateAnotherPathOrOrigin() throws Exception {
        for (String id : new String[]{"../login", "a?command=disarm", "https://elsewhere.invalid", "a%2Fdisarm", "a\r\nX: y"}) {
            JSONObject data = identities();
            data.getJSONArray("data").getJSONObject(0).getJSONObject("relationships").getJSONObject("selectedSystem")
                .put("data", ref(id, "systems/system"));
            transport.responses.add(json(data));
            assertEquals(AdtPortalClient.Status.UNSUPPORTED, client.query(clock.now + 5_000).status);
        }
        assertEquals(5, transport.requests.size());
        for (AdtPortalClient.Request request : transport.requests) assertEquals(AdtPortalClient.ORIGIN + "/web/api/identities", request.url);
    }

    @Test public void sessionHeadersAreBoundedAndRotatedCookiesApplyToNextRead() throws Exception {
        AdtPortalClient.Response first = json(identities());
        transport.responses.add(new AdtPortalClient.Response(200, first.contentType, first.body,
            Collections.singletonList("afg=rotated-value; Secure; HttpOnly; Path=/")));
        transport.responses.add(json(system())); transport.responses.add(json(partition(1, 1)));
        assertEquals(AdtPortalClient.Status.READY, client.query(5_000).status);
        assertEquals("inert-afg", transport.requests.get(0).headers.get("ajaxrequestuniquekey"));
        assertEquals("rotated-value", transport.requests.get(1).headers.get("ajaxrequestuniquekey"));
        assertEquals(1, session.saved.size());
        assertEquals(1, session.persistCalls);
        Map<String, String> headers = transport.requests.get(0).headers;
        assertEquals("application/vnd.api+json", headers.get("Accept"));
        assertEquals("no-cache, no-store", headers.get("Cache-Control"));
        assertEquals(AdtPortalClient.ORIGIN + "/web/system/home", headers.get("Referer"));
        try { headers.put("Origin", "https://elsewhere.invalid"); fail("Headers must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void missingDuplicateAndInjectedSessionHeadersDoNotMakeRequests() {
        session.cookie = "";
        assertEquals(AdtPortalClient.Status.LOGIN_REQUIRED, client.query(5_000).status);
        session.cookie = "afg=one; afg=two";
        assertEquals(AdtPortalClient.Status.VERIFY_LOGIN, client.query(5_000).status);
        session.cookie = "afg=one\r\nInjected: header";
        assertEquals(AdtPortalClient.Status.LOGIN_REQUIRED, client.query(5_000).status);
        session.cookie = "afg=inert-afg"; session.agent = "agent\nInjected: header";
        assertEquals(AdtPortalClient.Status.UNSUPPORTED, client.query(5_000).status);
        assertTrue(transport.requests.isEmpty());
    }

    @Test public void loginMfaRedirectAndServerErrorsAreDistinctWithoutEchoingPrivateData() {
        int[] codes = {401, 403, 423, 302, 500};
        AdtPortalClient.Status[] statuses = {AdtPortalClient.Status.LOGIN_REQUIRED, AdtPortalClient.Status.VERIFY_LOGIN,
            AdtPortalClient.Status.VERIFY_LOGIN, AdtPortalClient.Status.LOGIN_REQUIRED, AdtPortalClient.Status.UNAVAILABLE};
        for (int i = 0; i < codes.length; i++) {
            transport.responses.add(new AdtPortalClient.Response(codes[i], "text/html", SECRET.getBytes(StandardCharsets.UTF_8), null));
            AdtPortalClient.Result result = client.query(clock.now + 5_000);
            assertEquals(statuses[i], result.status); assertEquals(AlarmStateProtocol.State.UNKNOWN, result.state);
            assertEquals("", result.systemId); assertEquals("", result.partitionLabel);
            assertFalse(result.toString().contains(SECRET));
        }
        transport.responses.add(new AdtPortalClient.Response(200, "text/html", SECRET.getBytes(StandardCharsets.UTF_8), null));
        assertEquals(AdtPortalClient.Status.LOGIN_REQUIRED, client.query(clock.now + 5_000).status);
        assertEquals(6, transport.requests.size()); // Redirects never cause a follow-up request.
        for (AdtPortalClient.Request request : transport.requests) assertFalse(request.toString().contains(SECRET));
    }

    @Test public void malformedOversizedAndTrailingResponsesDoNotExposeOrCacheState() throws Exception {
        enqueue(1, 1); assertEquals(AdtPortalClient.Status.READY, client.query(5_000).status);
        for (byte[] bytes : new byte[][] {new byte[AdtPortalClient.MAX_BODY_BYTES + 1],
                ("{\"private\":\"" + SECRET + "\"}").getBytes(StandardCharsets.UTF_8),
                (identities().toString() + " trailing").getBytes(StandardCharsets.UTF_8), {(byte) 0xc3, (byte) 0x28}}) {
            transport.responses.add(new AdtPortalClient.Response(200, "application/json", bytes, null));
            AdtPortalClient.Result result = client.query(clock.now + 5_000);
            assertNotEquals(AdtPortalClient.Status.READY, result.status);
            assertEquals(AlarmStateProtocol.State.UNKNOWN, result.state); assertEquals("", result.systemId);
            assertFalse(result.toString().contains(SECRET));
        }
    }

    @Test public void expiredDeadlineStopsBetweenReadsAndCallerCannotExtendMaximum() throws Exception {
        assertEquals(AdtPortalClient.Status.UNAVAILABLE, client.query(clock.now).status);
        assertTrue(transport.requests.isEmpty());
        transport.responses.add(json(identities()));
        assertEquals(AdtPortalClient.Status.UNAVAILABLE, client.query(clock.now + 50).status);
        assertEquals(1, transport.requests.size());
        enqueue(1, 1);
        long started = clock.now;
        assertEquals(AdtPortalClient.Status.READY, client.query(Long.MAX_VALUE).status);
        assertEquals(started + AdtPortalClient.MAX_QUERY_MS, (long) transport.deadlines.get(1));
    }

    @Test public void productionHttpTransportUsesOnlyGetNoRedirectsNoCacheAndAlwaysDisconnects() throws Exception {
        Deque<AdtPortalClient.Response> replies = new ArrayDeque<>(Arrays.asList(json(identities()), json(system()), json(partition(1, 1))));
        List<FakeConnection> connections = new ArrayList<>();
        AdtPortalClient.HttpTransport http = new AdtPortalClient.HttpTransport(clock, url -> {
            FakeConnection connection = new FakeConnection(url, replies.remove()); connections.add(connection); return connection;
        });
        AdtPortalClient.Result result = new AdtPortalClient(session, http, clock).query(5_000);
        assertEquals(AdtPortalClient.Status.READY, result.status); assertEquals(3, connections.size());
        for (FakeConnection connection : connections) {
            assertEquals("GET", connection.getRequestMethod()); assertFalse(connection.getInstanceFollowRedirects());
            assertFalse(connection.getDoOutput()); assertFalse(connection.getUseCaches()); assertTrue(connection.disconnected);
            assertTrue(connection.getConnectTimeout() > 0 && connection.getConnectTimeout() <= 3000);
            assertTrue(connection.getReadTimeout() > 0 && connection.getReadTimeout() <= 3000);
            assertEquals("www.alarm.com", connection.getURL().getHost()); assertEquals("https", connection.getURL().getProtocol());
            assertEquals("inert-afg", connection.getRequestProperty("ajaxrequestuniquekey"));
        }
    }

    private void enqueue(int actual, int desired) throws Exception { enqueue(identities(), system(), partition(actual, desired)); }
    private void enqueue(JSONObject... documents) { for (JSONObject document : documents) transport.responses.add(json(document)); }
    private static AdtPortalClient.Response json(JSONObject value) {
        return new AdtPortalClient.Response(200, "application/vnd.api+json; charset=utf-8", value.toString().getBytes(StandardCharsets.UTF_8), null);
    }
    private static JSONObject ref(String id, String type) throws Exception { return new JSONObject().put("id", id).put("type", type); }
    private static JSONObject identity() throws Exception {
        return ref("inert-identity", "identity").put("relationships", new JSONObject().put("selectedSystem",
            new JSONObject().put("data", ref(SYSTEM, "systems/system"))));
    }
    private static JSONObject identities() throws Exception { return new JSONObject().put("data", new JSONArray().put(identity())); }
    private static JSONObject system() throws Exception {
        return new JSONObject().put("data", ref(SYSTEM, "systems/system")
            .put("attributes", new JSONObject().put("description", "Inert Home"))
            .put("relationships", new JSONObject().put("partitions", new JSONObject().put("data",
                new JSONArray().put(ref(PARTITION, "devices/partition"))))));
    }
    private static JSONObject partition(int actual, int desired) throws Exception {
        return new JSONObject().put("data", ref(PARTITION, "devices/partition").put("attributes", new JSONObject()
            .put("description", "Inert System").put("hasState", true).put("state", actual).put("desiredState", desired)));
    }
    private static final class FakeClock implements AdtPortalClient.Clock {
        long now = 1_000;
        @Override public long elapsed() { return now; }
    }
    private static final class FakeSession implements AdtPortalClient.Session {
        String cookie = "session=" + SECRET + "; afg=inert-afg", agent = "Inert-Agent/1";
        final List<String> saved = new ArrayList<>();
        int persistCalls;
        @Override public void persist() { persistCalls++; }
        @Override public String cookies() { return cookie; }
        @Override public String userAgent() { return agent; }
        @Override public void storeCookie(String value) { saved.add(value); cookie = "session=" + SECRET + "; " + value.split(";", 2)[0]; }
    }
    private static final class FakeTransport implements AdtPortalClient.Transport {
        final Deque<AdtPortalClient.Response> responses = new ArrayDeque<>();
        final List<AdtPortalClient.Request> requests = new ArrayList<>();
        final List<Long> deadlines = new ArrayList<>();
        final FakeClock clock;
        FakeTransport(FakeClock clock) { this.clock = clock; }
        @Override public AdtPortalClient.Response get(AdtPortalClient.Request request, long deadline) {
            requests.add(request); deadlines.add(deadline); clock.now += 100;
            assertFalse("Unexpected network operation", responses.isEmpty()); return responses.remove();
        }
    }
    private static final class FakeConnection extends HttpURLConnection {
        final AdtPortalClient.Response response;
        boolean disconnected;
        FakeConnection(URL url, AdtPortalClient.Response response) { super(url); this.response = response; }
        @Override public int getResponseCode() { return response.code; }
        @Override public String getContentType() { return response.contentType; }
        @Override public long getContentLengthLong() { return response.body.length; }
        @Override public Map<String, List<String>> getHeaderFields() { return Collections.emptyMap(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response.body); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() throws IOException { }
    }
}
