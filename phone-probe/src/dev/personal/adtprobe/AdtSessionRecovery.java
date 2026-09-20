package dev.personal.adtprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Optional credential login, followed by a read of the previously selected home. No alarm actions. */
final class AdtSessionRecovery {
    static final String PREFERENCES = "adt_login_recovery";
    static final long RETRY_DELAY_MS = 5 * 60_000;
    /** A background login plus its verifying read get this much, independent of the read that noticed. */
    static final long BACKGROUND_BUDGET_MS = 20_000;
    /** A queued login may start only while the user request that asked for it is this recent. */
    static final long MAX_QUEUE_AGE_MS = 20_000;
    private static final ReentrantLock LOGIN_LOCK = new ReentrantLock();
    private static final Object COOKIE_LOCK = new Object();
    private static long epoch, websiteOwner, testGeneration;
    private static QueuedAttempt queuedAttempt;

    private static final class QueuedAttempt {
        final long requestedElapsed, sessionEpoch;
        final String credentialVersion;
        QueuedAttempt(long requestedElapsed, long sessionEpoch, String credentialVersion) {
            this.requestedElapsed = requestedElapsed; this.sessionEpoch = sessionEpoch;
            this.credentialVersion = credentialVersion;
        }
    }

    interface Sessions { AdtPortalClient.Session create(Context context, long deadline) throws Exception; }
    interface Login { AdtLoginClient.Result login(AdtPortalClient.Session session, String username, char[] password, long deadline); }
    interface Read { AdtPortalClient.Result read(AdtPortalClient.Session session, AdtPortalSession.Binding binding, long deadline); }
    interface Credentials {
        String version(Context context);
        AdtCredentialStore.Credentials load(Context context);
    }
    static Sessions sessions = AdtSessionRecovery::createSession;
    static Login loginOperation = (session, username, password, deadline) -> new AdtLoginClient(session).login(username, password, deadline);
    static Read readOperation = (session, binding, deadline) -> new AdtPortalClient(session).status(binding.systemId, binding.partitionId, deadline);
    static Credentials credentials = new Credentials() {
        @Override public String version(Context context) { return AdtCredentialStore.version(context); }
        @Override public AdtCredentialStore.Credentials load(Context context) { return AdtCredentialStore.load(context); }
    };

    static final class Check {
        final boolean ready;
        final String message;
        Check(boolean ready, String message) { this.ready = ready; this.message = message; }
    }

    private static final class Attempt {
        final AdtPortalClient.Result result;
        final String code;
        Attempt(AdtPortalClient.Result result, String code) { this.result = result; this.code = code; }
    }

    private AdtSessionRecovery() { }

    /** Invalidate in-flight cookie writes before an interactive website can start a newer login. */
    static long beginInteractiveSignIn() {
        synchronized (COOKIE_LOCK) { websiteOwner = ++epoch; return websiteOwner; }
    }
    static void endInteractiveSignIn(long owner) {
        synchronized (COOKIE_LOCK) {
            if (owner != 0 && websiteOwner == owner) { websiteOwner = 0; epoch++; }
        }
    }
    static void cancelTest() { synchronized (COOKIE_LOCK) { testGeneration++; } }

    /** A status response begun before a newer login must not overwrite that login's cookies. */
    static AdtPortalClient.Session guardSession(AdtPortalClient.Session delegate) {
        final long captured;
        synchronized (COOKIE_LOCK) { captured = epoch; }
        return new AdtPortalClient.Session() {
            private void check() {
                if (epoch != captured || LOGIN_LOCK.isLocked()) throw new AdtPortalClient.SessionUnavailable("Session replaced");
            }
            @Override public String origin() { synchronized (COOKIE_LOCK) { check(); return delegate.origin(); } }
            @Override public String cookies() { synchronized (COOKIE_LOCK) { check(); return delegate.cookies(); } }
            @Override public String cookies(String url) { synchronized (COOKIE_LOCK) { check(); return delegate.cookies(url); } }
            @Override public String userAgent() { synchronized (COOKIE_LOCK) { check(); return delegate.userAgent(); } }
            @Override public void storeCookie(String cookie) { synchronized (COOKIE_LOCK) { check(); delegate.storeCookie(cookie); } }
            @Override public void storeCookie(String url, String cookie) { synchronized (COOKIE_LOCK) { check(); delegate.storeCookie(url, cookie); } }
            @Override public void persist() { synchronized (COOKIE_LOCK) { check(); delegate.persist(); } }
        };
    }

    /**
     * Worker only, after a user-requested status GET, which returns its failure at once. The
     * original request arrival time bounds queue lifetime; passive reads must never call this.
     * Queues one
     * bounded login on the reads worker with its own budget rather than the read's leftover
     * deadline; after a verified matching-home read, PhoneAlarmState reads once through the
     * normal path and tells the watch. Never replays a watch command.
     */
    static boolean recoverLater(Context context, AdtPortalSession.Binding binding, AdtPortalClient.Result failed,
            long requestedElapsed) {
        if (!authenticationFailure(failed) || binding == null
                || !recentRequest(requestedElapsed, SystemClock.elapsedRealtime())) return false;
        Context app = context.getApplicationContext();
        if (!worthAttempting(app)) return false;
        final QueuedAttempt next;
        synchronized (COOKIE_LOCK) {
            // A timer delayed behind other work must not revive an old request. Once it expires,
            // a newer user request can replace it; identity checks make the old callback inert.
            long now = SystemClock.elapsedRealtime();
            if (!recentRequest(requestedElapsed, now)) return false;
            if (queuedAttempt != null && recentRequest(queuedAttempt.requestedElapsed, now)) return false;
            String version = credentials.version(app);
            if (version == null || version.isEmpty()) return false;
            queuedAttempt = next = new QueuedAttempt(requestedElapsed, epoch, version);
        }
        try {
            PhoneAlarmState.scheduleRecovery(app, () -> {
                synchronized (COOKIE_LOCK) {
                    if (queuedAttempt != next) return false;
                    queuedAttempt = null;
                }
                return usable(attempt(app, binding, SystemClock.elapsedRealtime() + BACKGROUND_BUDGET_MS, false, next).result);
            });
        } catch (RuntimeException error) {
            synchronized (COOKIE_LOCK) { if (queuedAttempt == next) queuedAttempt = null; }
            return false;
        }
        return true;
    }

    private static boolean recentRequest(long requestedElapsed, long now) {
        return requestedElapsed >= 0 && now >= requestedElapsed && now - requestedElapsed < MAX_QUEUE_AGE_MS;
    }

    /** The cheap part of attempt's own checks, so a signed-out watch poll does not queue no-op work. */
    private static boolean worthAttempting(Context app) {
        String version = credentials.version(app);
        if (version == null || version.isEmpty()) return false;
        synchronized (COOKIE_LOCK) {
            if (websiteOwner != 0 || LOGIN_LOCK.isLocked()) return false;
            SharedPreferences prefs = preferences(app);
            if (!version.equals(prefs.getString("version", "")) || !prefs.getBoolean("enabled", false)
                    || prefs.getBoolean("blocked", false)) return false;
            long last = prefs.getLong("attemptWall", 0), now = System.currentTimeMillis();
            return now >= last && now - last >= RETRY_DELAY_MS;
        }
    }

    /** Explicit phone-only test also works while the old session is still valid. */
    static Check test(Context context, long deadline) {
        Context app = context.getApplicationContext();
        AdtPortalSession.Binding binding = AdtPortalSession.binding(app);
        if (binding == null) return new Check(false, "Choose your ADT system in live status setup first.");
        Attempt attempt = attempt(app, binding, deadline, true);
        if (usable(attempt.result))
            return new Check(true, "Automatic login worked and ADT verified your selected home. Lock your phone and refresh the watch.");
        String message;
        switch (attempt.code) {
            case "DISABLED": message = "Save your login details first."; break;
            case "WEBSITE_OPEN": message = "Finish the ADT website sign-in and tap Use this ADT system, then test again."; break;
            case "BUSY": message = "A login check is already running. Try again shortly."; break;
            case "CANCELLED": message = "Login check cancelled. No alarm command was sent."; break;
            case "CREDENTIALS": message = "Saved login could not be read. Forget it and save it again."; break;
            case "COOLDOWN": message = "Automatic login is paused. Check your details, then use Test saved login."; break;
            default: message = "Automatic login could not be verified. Check your details or complete verification through Open ADT sign-in.";
        }
        return new Check(false, message + "\nCheck code: " + attempt.code);
    }

    static boolean authenticationFailure(AdtPortalClient.Result result) {
        return result != null && (result.status == AdtPortalClient.Status.LOGIN_REQUIRED
            || result.status == AdtPortalClient.Status.VERIFY_LOGIN);
    }

    private static boolean usable(AdtPortalClient.Result result) {
        return result != null && (result.status == AdtPortalClient.Status.READY || result.status == AdtPortalClient.Status.BUSY);
    }

    private static Attempt attempt(Context app, AdtPortalSession.Binding binding, long deadline, boolean manual) {
        return attempt(app, binding, deadline, manual, null);
    }

    private static Attempt attempt(Context app, AdtPortalSession.Binding binding, long deadline, boolean manual,
            QueuedAttempt queued) {
        String version = credentials.version(app);
        if (version == null || version.isEmpty() || binding == null) return new Attempt(null, "DISABLED");
        long initialEpoch;
        final long startedTest;
        synchronized (COOKIE_LOCK) {
            if (!manual && (queued == null || !recentRequest(queued.requestedElapsed, SystemClock.elapsedRealtime())
                    || epoch != queued.sessionEpoch || !version.equals(queued.credentialVersion)))
                return new Attempt(null, "CANCELLED");
            if (websiteOwner != 0) return new Attempt(null, "WEBSITE_OPEN");
            initialEpoch = epoch; startedTest = testGeneration;
        }
        boolean locked = false;
        try {
            // Do not queue a second password submission behind an in-flight login.
            locked = LOGIN_LOCK.tryLock();
            if (!locked) return new Attempt(null, "BUSY");
            if (SystemClock.elapsedRealtime() >= deadline) return new Attempt(null, "TIMEOUT");
            final long startedEpoch;
            synchronized (COOKIE_LOCK) {
                if (!valid(app, binding, version, initialEpoch, startedTest, manual, deadline)) return new Attempt(null, "CANCELLED");
                SharedPreferences prefs = preferences(app);
                boolean same = version.equals(prefs.getString("version", ""));
                long last = prefs.getLong("attemptWall", 0), now = System.currentTimeMillis();
                // Saving a password is not enough: the phone's explicit test must first verify
                // that those credentials can read the already selected home.
                if (!manual && (!same || !prefs.getBoolean("enabled", false))) return new Attempt(null, "DISABLED");
                if (!manual && same && (prefs.getBoolean("blocked", false) || now < last || now - last < RETRY_DELAY_MS))
                    return new Attempt(null, "COOLDOWN");
                // Persist before the password POST: process death cannot reset the retry interval.
                if (!prefs.edit().clear().putString("version", version).putLong("attemptWall", now)
                        .putString("code", "STARTED").putBoolean("blocked", false)
                        .putBoolean("enabled", !manual && same && prefs.getBoolean("enabled", false)).commit())
                    return new Attempt(null, "STORAGE");
                startedEpoch = ++epoch;
            }
            try (AdtCredentialStore.Credentials secret = credentials.load(app)) {
                if (secret == null || !version.equals(secret.version)) {
                    record(app, version, "CREDENTIALS", true); return new Attempt(null, "CREDENTIALS");
                }
                AdtPortalClient.Session delegate = sessions.create(app, deadline);
                AdtPortalClient.Session guarded = new AdtPortalClient.Session() {
                    private void check() {
                        if (!valid(app, binding, version, startedEpoch, startedTest, manual, deadline))
                            throw new AdtPortalClient.SessionUnavailable("Login cancelled");
                    }
                    @Override public String origin() { synchronized (COOKIE_LOCK) { check(); return delegate.origin(); } }
                    @Override public String cookies() { synchronized (COOKIE_LOCK) { check(); return delegate.cookies(); } }
                    @Override public String cookies(String url) { synchronized (COOKIE_LOCK) { check(); return delegate.cookies(url); } }
                    @Override public String userAgent() { synchronized (COOKIE_LOCK) { check(); return delegate.userAgent(); } }
                    @Override public void storeCookie(String cookie) { synchronized (COOKIE_LOCK) { check(); delegate.storeCookie(cookie); } }
                    @Override public void storeCookie(String url, String cookie) { synchronized (COOKIE_LOCK) { check(); delegate.storeCookie(url, cookie); } }
                    @Override public void persist() { synchronized (COOKIE_LOCK) { check(); delegate.persist(); } }
                };
                AdtLoginClient.Result login = loginOperation.login(guarded, secret.username, secret.password, deadline);
                synchronized (COOKIE_LOCK) {
                    if (!valid(app, binding, version, startedEpoch, startedTest, manual, deadline)) return new Attempt(null, "CANCELLED");
                }
                if (login == null || login.status != AdtLoginClient.Status.SUBMITTED) {
                    String code = login == null ? "LOGIN/UNAVAILABLE" : login.diagnosticCode();
                    // Pause only once the credentials were judged: a rejected or challenged attempt, or
                    // any failure after the POST left the phone. A login page the parser could not use
                    // cost one GET and no submission; the ordinary retry delay covers it.
                    boolean judged = login != null && (login.status == AdtLoginClient.Status.REJECTED
                        || login.status == AdtLoginClient.Status.VERIFY_LOGIN
                        || login.stage == AdtLoginClient.Stage.SUBMIT && login.status != AdtLoginClient.Status.UNAVAILABLE);
                    record(app, version, code, judged);
                    return new Attempt(null, code);
                }
                // Auth cookies alone are not proof. Read and validate exactly the saved home.
                AdtPortalClient.Result read = readOperation.read(guarded, binding, deadline);
                synchronized (COOKIE_LOCK) {
                    if (!valid(app, binding, version, startedEpoch, startedTest, manual, deadline)) return new Attempt(null, "CANCELLED");
                    boolean verified = usable(read) && binding.systemId.equals(read.systemId) && binding.partitionId.equals(read.partitionId);
                    String code = verified ? "READY" : read == null ? "VERIFY/UNAVAILABLE" : "VERIFY/" + read.diagnosticCode();
                    record(app, version, code, !verified && (read == null || read.status != AdtPortalClient.Status.UNAVAILABLE));
                    if (verified) {
                        // Replace the shared session only after isolated authentication and a
                        // matching-home read. An old valid session cannot verify a new password.
                        if (delegate instanceof AdtLoginSession) ((AdtLoginSession) delegate).commit();
                        if (!valid(app, binding, version, startedEpoch, startedTest, manual, deadline)) return new Attempt(null, "CANCELLED");
                        String verifiedOrigin = guarded.origin();
                        AdtPortalSession.recordVerifiedOrigin(app, verifiedOrigin);
                        if (!preferences(app).edit().putBoolean("enabled", true).commit()) return new Attempt(null, "STORAGE");
                        return new Attempt(read, code);
                    }
                    return new Attempt(null, code);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); record(app, version, "CANCELLED", false); return new Attempt(null, "CANCELLED");
        } catch (Exception ignored) {
            // Only closed diagnostics; credentials, bodies and exception messages are never logged.
            record(app, version, "UNAVAILABLE", false); return new Attempt(null, "UNAVAILABLE");
        } finally { if (locked) LOGIN_LOCK.unlock(); }
    }

    private static boolean valid(Context app, AdtPortalSession.Binding binding, String version,
            long startedEpoch, long startedTest, boolean manual, long deadline) {
        return !Thread.currentThread().isInterrupted() && websiteOwner == 0 && epoch == startedEpoch && (!manual || testGeneration == startedTest)
            && SystemClock.elapsedRealtime() < deadline && AdtPortalSession.valid(app, binding)
            && version.equals(credentials.version(app));
    }

    private static void record(Context app, String version, String code, boolean blocked) {
        synchronized (COOKIE_LOCK) {
            if (version.equals(credentials.version(app))) preferences(app).edit()
                .putString("version", version).putString("code", code).putBoolean("blocked", blocked).commit();
        }
    }

    private static AdtPortalClient.Session createSession(Context app, long deadline) throws Exception {
        FutureTask<AdtPortalClient.Session> task = new FutureTask<>(() -> new AdtLoginSession(AdtPortalSession.loginSession(app)));
        new Handler(Looper.getMainLooper()).post(task);
        try { return task.get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS); }
        finally { task.cancel(false); }
    }

    /** What the phone should say about automatic login next to a sign-in problem; null when none is saved. */
    static String describe(Context context) {
        Context app = context.getApplicationContext();
        String version = credentials.version(app);
        if (version == null || version.isEmpty()) return null;
        synchronized (COOKIE_LOCK) {
            if (websiteOwner != 0) return "Automatic login waits until the ADT sign-in page is closed.";
            SharedPreferences prefs = preferences(app);
            if (!version.equals(prefs.getString("version", "")) || !prefs.getBoolean("enabled", false))
                return "Automatic login is saved but not verified: open Automatic ADT login and run Test saved login.";
            String code = prefs.getString("code", "");
            if (prefs.getBoolean("blocked", false))
                return "Automatic login is paused after " + code + ". Sign in through Set up ADT live status and choose"
                    + " the system again, or run Test saved login.";
            long last = prefs.getLong("attemptWall", 0), now = System.currentTimeMillis();
            if (!"READY".equals(code) && now >= last && now - last < RETRY_DELAY_MS)
                return "Automatic login last ended with " + code + "; it retries in about " + minutesLeft(now - last) + ".";
            return "Automatic login is enabled.";
        }
    }
    private static String minutesLeft(long since) {
        long minutes = (RETRY_DELAY_MS - since + 59_999) / 60_000;
        return minutes <= 1 ? "a minute" : minutes + " minutes";
    }

    /**
     * The owner signed in on the website and chose the system again: the attention a pause was
     * waiting for. Attempts resume and the retry delay is cleared; whether the saved credentials
     * are verified (enabled) is unchanged, so an unverified login still needs its explicit test.
     */
    static void interactiveSignInCompleted(Context context) {
        synchronized (COOKIE_LOCK) {
            SharedPreferences prefs = preferences(context.getApplicationContext());
            if (prefs.contains("version") && (prefs.getBoolean("blocked", false) || prefs.contains("attemptWall")))
                prefs.edit().putBoolean("blocked", false).remove("attemptWall").commit();
        }
    }

    private static SharedPreferences preferences(Context app) { return app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE); }
}
