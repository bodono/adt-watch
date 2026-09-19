package dev.personal.adtprobe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic forms and inert transports only. Tests never sign into an account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class AdtLoginClientTest {
    private static final String ORIGIN = AdtLoginClient.ORIGIN;
    private static final String USER_FIELD = "ctl00$ContentPlaceHolder1$loginform$txtUserName";
    private static final String SECRET = "inert-password &+=é/";
    private FakeSession session;
    private FakeClock clock;
    private FakeTransport transport;
    private AdtLoginClient client;
    @Before public void setup() {
        session = new FakeSession(); clock = new FakeClock(); transport = new FakeTransport(clock);
        client = new AdtLoginClient(session, transport, clock);
    }
    @Test public void fixedCredentialPostUsesFreshFormCookiesUtf8EncodingAndOfficialJavascriptValues() {
        transport.responses.add(html(form(), Arrays.asList("formToken=inert; Path=/")));
        transport.responses.add(redirect(302, "/web/system/home", Arrays.asList("afg=inert-key; Path=/web")));
        char[] password = SECRET.toCharArray();
        AdtLoginClient.Result result = client.login("inert+user@example.test", password, clock.now + 10_000);
        assertEquals(AdtLoginClient.Status.SUBMITTED, result.status);
        assertEquals("SUBMIT/NONE/302", result.diagnosticCode());
        assertEquals(2, transport.requests.size());
        assertEquals("GET", transport.requests.get(0).method);
        AdtLoginClient.Request post = transport.requests.get(1);
        assertEquals("POST", post.method); assertEquals(ORIGIN + "/web/Default.aspx", post.url);
        assertEquals("text/html,application/xhtml+xml", post.headers.get("Accept"));
        assertEquals("inert-browser-agent", post.headers.get("User-Agent"));
        assertEquals(ORIGIN, post.headers.get("Origin")); assertEquals(ORIGIN + "/login", post.headers.get("Referer"));
        assertTrue(post.headers.get("Cookie").contains("twoFactorAuthenticationId=inert-trust"));
        assertTrue(post.headers.get("Cookie").contains("formToken=inert"));
        Map<String, String> submitted = decode(transport.bodies.get(1));
        assertEquals("inert+user@example.test", submitted.get(USER_FIELD)); assertEquals(SECRET, submitted.get("txtPassword"));
        assertEquals("state&+\"'", submitted.get("__VIEWSTATE"));
        assertEquals("1", submitted.get("IsFromNewSite")); assertEquals("1", submitted.get("JavaScriptTest"));
        assertEquals("", submitted.get("__VIEWSTATEENCRYPTED")); assertFalse(submitted.containsKey("untrustedExtra"));
        assertEquals(Arrays.asList(ORIGIN + "/login", ORIGIN + "/web/Default.aspx"), session.readUrls);
        assertEquals(Arrays.asList(ORIGIN + "/login", ORIGIN + "/web/Default.aspx"), session.writeUrls);
        assertEquals(1, session.persisted); assertArrayEquals(SECRET.toCharArray(), password);
        assertArrayEquals("The client's encoded password buffer is erased", new byte[post.body.length], post.body);
    }
    @Test public void onlyKnownAlarmComOriginIsAllowedAndInvalidInputNeverSendsCredentials() {
        for (String origin : Arrays.asList("https://smartservices.adt.co.uk", "http://www.alarm.com", "https://evil.test", "https://www.alarm.com.evil.test")) {
            session.origin = origin;
            assertEquals(AdtLoginClient.Status.UNSUPPORTED, login().status);
        }
        session.origin = ORIGIN;
        assertEquals(AdtLoginClient.Status.REJECTED, client.login("", SECRET.toCharArray(), clock.now + 1000).status);
        assertEquals(AdtLoginClient.Status.REJECTED, client.login("inert", new char[0], clock.now + 1000).status);
        assertEquals(0, transport.requests.size());
    }
    @Test public void getRedirectsStayOnKnownSameOriginLoginPathsAndAreBounded() {
        transport.responses.add(redirect(302, "/web/Default.aspx", null));
        transport.responses.add(html(form().replace("action='./login'", "action='/web/Default.aspx'"), null));
        transport.responses.add(redirect(303, "/web/system/home", null));
        assertEquals(AdtLoginClient.Status.SUBMITTED, login().status);
        assertEquals(3, transport.requests.size()); assertEquals("GET", transport.requests.get(1).method);
        setup();
        for (int i = 0; i < 3; i++) transport.responses.add(redirect(302, "/login", null));
        assertEquals(AdtLoginClient.Status.UNSUPPORTED, login().status);
        assertEquals(3, transport.requests.size()); assertEquals(0, posts());
    }
    @Test public void credentialRedirectsNeverCauseAnotherRequestIncluding307And308() {
        for (int code : Arrays.asList(301, 302, 303, 307, 308)) {
            setup(); transport.responses.add(html(form(), null)); transport.responses.add(redirect(code, "/web/system/home", null));
            assertEquals(AdtLoginClient.Status.SUBMITTED, login().status);
            assertEquals(2, transport.requests.size()); assertEquals(1, posts());
        }
    }
    @Test public void postRedirectLocationIsIgnoredRegardlessOfDestinationAndIsNeverAuthenticationProof() {
        for (String destination : Arrays.asList("/web/system/home?login=complete", ORIGIN + "/web/system/home/?inert=a%2Bb",
                "/web/system/home?login=complete#fragment", "https://inert@www.alarm.com/web/system/home?login=complete",
                "https://evil.test/collect", "/login?return=/web/system/home", "/web/verification", "not a valid URL", null)) {
            setup(); transport.responses.add(html(form(), null)); transport.responses.add(redirect(302, destination, null));
            AdtLoginClient.Result result = login();
            assertEquals(AdtLoginClient.Status.SUBMITTED, result.status);
            assertEquals("SUBMIT/NONE/302", result.diagnosticCode()); assertEquals(2, transport.requests.size()); assertEquals(1, posts());
            assertFalse(result.toString().contains("inert="));
            assertEquals(ORIGIN + "/web/Default.aspx", transport.requests.get(1).url);
            setup(); transport.responses.add(redirect(302, destination, null));
            assertEquals(AdtLoginClient.Status.UNSUPPORTED, login().status); assertEquals(0, posts());
        }
    }
    @Test public void foreignOrMalformedLocationsAndFormActionsCannotReceivePassword() {
        for (String location : Arrays.asList("https://evil.test/login", "//evil.test/login", "http://www.alarm.com/login",
                "https://www.alarm.com@evil.test/login", "/login?return=private", "/%6cogin", "/login#private")) {
            setup(); transport.responses.add(redirect(302, location, null));
            assertEquals(AdtLoginClient.Status.UNSUPPORTED, login().status); assertEquals(0, posts());
            setup(); transport.responses.add(html(form().replace("./login", location), null));
            assertEquals(AdtLoginClient.Status.UNSUPPORTED, login().status); assertEquals(0, posts());
            setup(); transport.responses.add(html(form(), null)); transport.responses.add(redirect(302, location, null));
            assertEquals(AdtLoginClient.Status.SUBMITTED, login().status); assertEquals(1, posts());
            assertEquals(2, transport.requests.size());
        }
    }
    @Test public void captchaAndNewVerificationRequireUserWithoutRetryingCredentials() {
        transport.responses.add(html(form().replace("</form>", "<textarea name='g-recaptcha-response'></textarea></form>"), null));
        assertEquals(AdtLoginClient.Status.VERIFY_LOGIN, login().status); assertEquals(0, posts());
        setup(); transport.responses.add(html(form(), null));
        transport.responses.add(html("<input autocomplete='one-time-code' name='code'>", null));
        assertEquals(AdtLoginClient.Status.VERIFY_LOGIN, login().status); assertEquals(1, posts());
        setup(); transport.responses.add(html(form(), null)); transport.responses.add(redirect(302, "/web/verification", null));
        assertEquals("SUBMIT/NONE/302", login().diagnosticCode()); assertEquals(1, posts());
    }
    @Test public void scriptsAndCommentsDoNotCreateFieldsOrFalseCaptchaChallenge() {
        transport.responses.add(html("<script>var key='g-recaptcha-response'; var x=\"<input name='txtPassword'>\";</script>"
            + "<!-- <input name='__VIEWSTATE' type='hidden' value='ignored'> -->" + form(), null));
        transport.responses.add(redirect(302, "/web/system/home", null));
        assertEquals(AdtLoginClient.Status.SUBMITTED, login().status);
    }
    @Test public void missingDuplicateMalformedAndOutOfFormInputsNeverReachPost() {
        List<String> broken = Arrays.asList(
            form().replace("name='__EVENTVALIDATION'", "name='unknown'"),
            form().replace("</form>", "<input type='hidden' name='__VIEWSTATE' value='duplicate'></form>"),
            form().replace("action='./login'", "action='./login' action='https://evil.test/'"),
            form().replace("type='password'", "type='text'"),
            form().replace("</form>", ""),
            form().replace("<input type='password'", "</form><input type='password'"),
            form().replace("state&amp;", "state&broken;"),
            form() + form());
        for (String body : broken) {
            setup(); transport.responses.add(html(body, null));
            assertEquals(AdtLoginClient.Status.UNSUPPORTED, login().status); assertEquals(0, posts());
        }
    }
    @Test public void badPasswordAndHttpFailuresHaveClosedDiagnosticsAndNoAutomaticRetries() {
        for (int code : Arrays.asList(401, 403, 409, 423, 429, 500)) {
            setup(); transport.responses.add(html(form(), null));
            transport.responses.add(new AdtLoginClient.Response(code, "text/html", SECRET.getBytes(StandardCharsets.UTF_8), null, null));
            AdtLoginClient.Result result = login();
            assertEquals("SUBMIT/HTTP/" + code, result.diagnosticCode()); assertEquals(1, posts());
            AdtLoginClient.Status expected = code == 401 || code == 429 ? AdtLoginClient.Status.REJECTED
                : code == 500 ? AdtLoginClient.Status.UNAVAILABLE : AdtLoginClient.Status.VERIFY_LOGIN;
            assertEquals(expected, result.status); assertFalse(result.toString().contains(SECRET));
        }
        setup(); transport.responses.add(html(form(), null)); transport.responses.add(html(form(), null));
        assertEquals(AdtLoginClient.Status.REJECTED, login().status); assertEquals(1, posts());
        setup(); transport.responses.add(html(form(), null)); transport.responses.add(redirect(302, "/login", null));
        assertEquals("The coordinator must reject an unauthenticated scoped GET", AdtLoginClient.Status.SUBMITTED, login().status);
        assertEquals(1, posts());
    }
    @Test public void arbitrary200PostHtmlCannotValidateNewCredentialsUsingAnExistingSession() {
        session.cookies = "twoFactorAuthenticationId=inert-trust; afg=old-valid-session";
        transport.responses.add(html(form(), null));
        transport.responses.add(html("<html><main>Temporary maintenance or an unrecognized login error</main></html>", null));
        AdtLoginClient.Result result = login();
        assertEquals(AdtLoginClient.Status.UNSUPPORTED, result.status);
        assertEquals("SUBMIT/FORM/200", result.diagnosticCode()); assertEquals(1, posts());
        assertEquals(2, transport.requests.size());
        assertTrue("Failure does not destructively clear the owner's existing session", session.cookies.contains("afg=old-valid-session"));
    }
    @Test public void deadlineExpirationBeforeOrDuringFormPreventsCredentialSubmission() {
        assertEquals("SESSION/TIMEOUT", client.login("inert", SECRET.toCharArray(), clock.now).diagnosticCode());
        assertEquals(0, transport.requests.size());
        transport.responses.add(html(form(), null)); transport.advance = 6000;
        assertEquals("FORM/TIMEOUT", client.login("inert", SECRET.toCharArray(), clock.now + 5000).diagnosticCode());
        assertEquals(0, posts());
        setup(); transport.responses.add(html(form(), null)); transport.advance = AdtLoginClient.MAX_LOGIN_MS;
        assertEquals(AdtLoginClient.Status.UNAVAILABLE, client.login("inert", SECRET.toCharArray(), Long.MAX_VALUE).status);
        assertEquals(0, posts());
    }
    @Test public void rejectedLateOrExceptionalPostDoesNotLeakOrRetainPasswordBuffer() {
        transport.responses.add(html(form(), null)); transport.failureOnPost = new IOException("private " + SECRET);
        AdtLoginClient.Result result = login();
        assertEquals("SUBMIT/IO", result.diagnosticCode()); assertEquals(1, posts());
        assertFalse(result.toString().contains(SECRET));
        AdtLoginClient.Request request = transport.requests.get(1);
        assertFalse(request.toString().contains(SECRET)); assertFalse(request.toString().contains("inert-trust"));
        assertArrayEquals(new byte[request.body.length], request.body);
        assertFalse(html(SECRET, null).toString().contains(SECRET));
    }
    @Test public void oversizedBodiesCookiesAndInvalidContentAreBoundedBeforePost() {
        transport.responses.add(new AdtLoginClient.Response(200, "text/html", new byte[AdtLoginClient.MAX_BODY_BYTES + 1], null, null));
        assertEquals("FORM/RESPONSE_SIZE", login().diagnosticCode()); assertEquals(0, posts());
        setup(); session.cookies = "bad\r\nheader";
        assertEquals("FORM/COOKIE_FORMAT", login().diagnosticCode()); assertEquals(0, transport.requests.size());
        setup(); transport.responses.add(new AdtLoginClient.Response(200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), null, null));
        assertEquals("FORM/CONTENT_TYPE/200", login().diagnosticCode()); assertEquals(0, posts());
    }
    @Test public void nativeTransportDisablesRedirectsAndWritesExactlyOneFixedLengthPost() throws Exception {
        final List<FakeConnection> connections = new ArrayList<>();
        AdtLoginClient.HttpTransport http = new AdtLoginClient.HttpTransport(clock, url -> {
            FakeConnection connection = new FakeConnection(url, connections.isEmpty() ? html(form(), null) : redirect(307, "/web/system/home", null));
            connections.add(connection); return connection;
        });
        AdtLoginClient nativeClient = new AdtLoginClient(session, http, clock);
        assertEquals(AdtLoginClient.Status.SUBMITTED, nativeClient.login("inert", SECRET.toCharArray(), clock.now + 10_000).status);
        assertEquals(2, connections.size());
        for (FakeConnection connection : connections) {
            assertFalse(connection.getInstanceFollowRedirects()); assertFalse(connection.getUseCaches());
            assertTrue(connection.disconnected); assertTrue(connection.getConnectTimeout() <= 3000); assertTrue(connection.getReadTimeout() <= 3000);
        }
        assertEquals("GET", connections.get(0).getRequestMethod()); assertFalse(connections.get(0).getDoOutput());
        FakeConnection post = connections.get(1); assertEquals("POST", post.getRequestMethod());
        assertEquals(ORIGIN + "/web/Default.aspx", post.getURL().toString());
        assertEquals(post.fixedLength(), post.output.size()); assertEquals(SECRET, decode(post.output.toByteArray()).get("txtPassword"));
    }
    @Test public void nativeTransportRefusesOversizedFormBeforeReadingIt() {
        FakeConnection connection;
        try { connection = new FakeConnection(new URL(ORIGIN + "/login"), html(form(), null)); }
        catch (Exception failure) { throw new AssertionError(failure); }
        connection.contentLength = AdtLoginClient.MAX_BODY_BYTES + 1L;
        AdtLoginClient nativeClient = new AdtLoginClient(session, new AdtLoginClient.HttpTransport(clock, url -> connection), clock);
        assertEquals("FORM/RESPONSE_SIZE", nativeClient.login("inert", SECRET.toCharArray(), clock.now + 1000).diagnosticCode());
        assertFalse(connection.inputOpened); assertTrue(connection.disconnected);
    }
    @Test public void cancellationOrDeadlineDuringStreamAcquisitionWritesNoCredentials() {
        for (boolean interrupted : Arrays.asList(false, true)) {
            setup();
            final List<FakeConnection> connections = new ArrayList<>();
            AdtLoginClient.HttpTransport http = new AdtLoginClient.HttpTransport(clock, url -> {
                FakeConnection connection = new FakeConnection(url, connections.isEmpty() ? html(form(), null) : redirect(302, "/web/system/home", null));
                if (!connections.isEmpty()) connection.onOutputOpen = () -> {
                    if (interrupted) Thread.currentThread().interrupt(); else clock.now += 10_000;
                };
                connections.add(connection); return connection;
            });
            try {
                AdtLoginClient nativeClient = new AdtLoginClient(session, http, clock);
                assertEquals("SUBMIT/TIMEOUT", nativeClient.login("inert", SECRET.toCharArray(), clock.now + 10_000).diagnosticCode());
                assertEquals(2, connections.size());
                assertEquals("No password bytes after a cancelled or late connection", 0, connections.get(1).output.size());
                assertTrue(connections.get(1).disconnected);
            } finally { Thread.interrupted(); }
        }
    }
    private AdtLoginClient.Result login() { return client.login("inert-user", SECRET.toCharArray(), clock.now + 10_000); }
    private int posts() { return (int) transport.requests.stream().filter(request -> "POST".equals(request.method)).count(); }
    private static String form() {
        return "<html><form id='aspnetForm' method='post' action='./login'>"
            + "<input type='hidden' name='__VIEWSTATE' value='state&amp;+&quot;&#39;'>"
            + "<input type='hidden' name='__VIEWSTATEGENERATOR' value='generator'>"
            + "<input type='hidden' name='__VIEWSTATEENCRYPTED' value=''>"
            + "<input type='hidden' name='__PREVIOUSPAGE' value='previous'>"
            + "<input type='hidden' name='__EVENTVALIDATION' value='validation'>"
            + "<input type='hidden' name='IsFromNewSite' value='1'><input type='hidden' name='JavaScriptTest' value=''>"
            + "<input type='hidden' name='loginFolder' value=''><input type='hidden' name='untrustedExtra' value='omit'>"
            + "<input type='text' name='" + USER_FIELD + "'><input type='password' name='txtPassword'>"
            + "<input type='checkbox' name='chkRememberMe'><input type='submit' id='signInButton'></form></html>";
    }
    private static AdtLoginClient.Response html(String body, List<String> cookies) {
        return new AdtLoginClient.Response(200, "text/html; charset=UTF-8", body.getBytes(StandardCharsets.UTF_8), cookies, null);
    }
    private static AdtLoginClient.Response redirect(int code, String location, List<String> cookies) {
        return new AdtLoginClient.Response(code, "text/html", new byte[0], cookies, location);
    }
    private static Map<String, String> decode(byte[] data) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : new String(data, StandardCharsets.UTF_8).split("&")) {
            String[] value = pair.split("=", 2);
            result.put(URLDecoder.decode(value[0], StandardCharsets.UTF_8), URLDecoder.decode(value.length == 2 ? value[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }
    private static final class FakeClock implements AdtLoginClient.Clock {
        long now = 1000; @Override public long elapsed() { return now; }
    }
    private static final class FakeSession implements AdtPortalClient.Session {
        String origin = ORIGIN, cookies = "twoFactorAuthenticationId=inert-trust";
        int persisted;
        final List<String> readUrls = new ArrayList<>(), writeUrls = new ArrayList<>();
        @Override public String origin() { return origin; }
        @Override public String cookies() { return cookies; }
        @Override public String cookies(String url) { readUrls.add(url); return cookies; }
        @Override public String userAgent() { return "inert-browser-agent"; }
        @Override public void storeCookie(String cookie) { cookies += "; " + cookie.split(";", 2)[0]; }
        @Override public void storeCookie(String url, String cookie) { writeUrls.add(url); storeCookie(cookie); }
        @Override public void persist() { persisted++; }
    }
    private static final class FakeTransport implements AdtLoginClient.Transport {
        final FakeClock clock; long advance = 100; IOException failureOnPost;
        final Deque<AdtLoginClient.Response> responses = new ArrayDeque<>();
        final List<AdtLoginClient.Request> requests = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        FakeTransport(FakeClock clock) { this.clock = clock; }
        @Override public AdtLoginClient.Response execute(AdtLoginClient.Request request, long deadline) throws IOException {
            requests.add(request); bodies.add(request.body == null ? null : request.body.clone()); clock.now += advance;
            if ("POST".equals(request.method) && failureOnPost != null) throw failureOnPost;
            if (responses.isEmpty()) throw new AssertionError("Unexpected extra login request");
            return responses.remove();
        }
    }
    private static final class FakeConnection extends HttpURLConnection {
        final AdtLoginClient.Response response; final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean disconnected, inputOpened; long contentLength = -1; Runnable onOutputOpen;
        FakeConnection(URL url, AdtLoginClient.Response response) { super(url); this.response = response; }
        int fixedLength() { return fixedContentLength; }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return response.code; }
        @Override public String getContentType() { return response.contentType; }
        @Override public long getContentLengthLong() { return contentLength; }
        @Override public Map<String, List<String>> getHeaderFields() { return Collections.singletonMap("Set-Cookie", response.setCookies); }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? response.location : null; }
        @Override public InputStream getInputStream() { inputOpened = true; return new ByteArrayInputStream(response.body); }
        @Override public OutputStream getOutputStream() { if (onOutputOpen != null) onOutputOpen.run(); return output; }
    }
}
