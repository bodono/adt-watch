package dev.personal.adtprobe;

import android.app.Activity;
import android.app.KeyguardManager;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

/** Official website sign-in and read-only selection of the alarm supplying watch status. */
public final class AdtPortalSetupActivity extends Activity {
    static final String LOGIN_URL = "https://smartservices.adt.co.uk/";
    private static final long QUERY_MS = 10_000, CHOICE_MS = 60_000;
    interface QueryOperation { AdtPortalClient.Result query(AdtPortalClient.Session session, long deadline); }
    static QueryOperation queryOperation = (session, deadline) -> new AdtPortalClient(session).query(deadline);
    static Executor queryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "adt-portal-status"); thread.setDaemon(true); return thread;
    });
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView status;
    private Button check, use;
    private AdtPortalClient.Session session;
    private AdtPortalClient.Result candidate;
    private FutureTask<Void> inFlight;
    private long queryStarted, deadline, candidateStarted;
    private int generation;
    private boolean resumed;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (candidate != null && !freshCandidate()) {
                candidate = null;
                status.setText("That status check has expired. Tap Check live status again.");
            }
            updateButtons(); handler.postDelayed(this, 500);
        }
    };

    @SuppressLint("SetJavaScriptEnabled") // ADT login requires JavaScript; navigation is restricted and no bridge is installed.
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(8), dp(16), dp(8)); column.setBackgroundColor(Color.rgb(17, 22, 30));
        setContentView(column);
        column.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            column.setPadding(dp(16) + edges.left, dp(8) + edges.top, dp(16) + edges.right, dp(8) + edges.bottom);
            return insets;
        });
        text(column, "ADT live status setup", 22);
        text(column, "Sign in to ADT below, then tap Check live status. This is a separate sign-in from the ADT app. "
            + "Choose the matching system to let the watch read its current status. These checks send no alarm commands.", 14);
        check = button(column, "Check live status", this::beginCheck);
        status = text(column, "Enter your ADT login and any verification code in the ADT page below.", 14);
        use = button(column, "Use this ADT system", this::chooseSystem);
        use.setVisibility(View.GONE);
        web = new WebView(this);
        web.setSaveEnabled(false);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        web.setWebViewClient(new PortalNavigation());
        column.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        session = AdtPortalSession.session(this);
        web.loadUrl(LOGIN_URL);
    }

    private void beginCheck() {
        if (!interactive() || inFlight != null) return;
        candidate = null; queryStarted = SystemClock.elapsedRealtime(); deadline = queryStarted + QUERY_MS;
        final int ticket = ++generation;
        final long queryDeadline = deadline;
        status.setText("Checking ADT…");
        recordDiagnostic("UI/CHECKING", "BUSY", 0);
        FutureTask<Void> work = new FutureTask<>(() -> {
            AdtPortalClient.Result result;
            try { result = queryOperation.query(session, queryDeadline); }
            catch (RuntimeException ignored) { result = null; }
            final AdtPortalClient.Result answer = result;
            handler.post(() -> complete(ticket, answer)); return null;
        });
        inFlight = work; updateButtons();
        handler.postDelayed(() -> {
            if (ticket != generation || inFlight == null) return;
            recordDiagnostic("UI/TIMEOUT", "UNAVAILABLE", Math.max(0, SystemClock.elapsedRealtime() - queryStarted));
            cancelQuery(); status.setText("ADT did not answer within 10 seconds. Try the status check again.\nCheck code: UI/TIMEOUT"); updateButtons();
        }, QUERY_MS);
        try { queryExecutor.execute(work); }
        catch (RuntimeException ignored) { complete(ticket, null); }
    }

    private void complete(int ticket, AdtPortalClient.Result result) {
        if (ticket != generation || inFlight == null || !resumed) return;
        inFlight = null;
        // Closed diagnostics only: no account IDs, state payloads, URLs, cookies or exception text.
        recordDiagnostic(result == null ? "UI/NO_RESULT" : result.diagnosticCode(),
            result == null ? "UNAVAILABLE" : result.status.name(), result == null ? 0 : result.elapsedMillis);
        long now = SystemClock.elapsedRealtime();
        if (now < queryStarted || now >= deadline) {
            status.setText("The ADT check expired. Tap Check live status again.");
        } else if (result != null && result.status == AdtPortalClient.Status.READY
                && AlarmStateProtocol.action(result.state) != null && AdtPortalSession.validId(result.systemId)
                && AdtPortalSession.validId(result.partitionId)) {
            candidate = result; candidateStarted = queryStarted;
            status.setText("ADT reports " + stateLabel(result.state) + ".\nSystem: " + display(result.systemLabel, result.systemId)
                + "\nPartition: " + display(result.partitionLabel, result.partitionId)
                + String.format(Locale.UK, "\nQuery: %.1f seconds.", Math.max(0, result.elapsedMillis) / 1000.0)
                + "\nBefore choosing, check that this is the same home as your configured Arm Stay and Disarm scenes.");
        } else status.setText(failureMessage(result == null ? null : result.status)
            + (result == null ? "" : "\nCheck code: " + result.diagnosticCode()));
        updateButtons();
    }

    private void recordDiagnostic(String code, String statusValue, long elapsed) {
        getSharedPreferences("adt_portal_diagnostics", MODE_PRIVATE).edit().clear()
            .putString("code", code).putString("status", statusValue).putLong("elapsedMillis", elapsed).apply();
    }

    private void chooseSystem() {
        if (!interactive() || !freshCandidate() || inFlight != null) return;
        AdtPortalClient.Result chosen = candidate;
        boolean saved = AdtPortalSession.bind(this, chosen.systemId, chosen.partitionId);
        candidate = null;
        status.setText(saved ? "ADT system selected. Only the system and partition identifiers were saved. "
            + "No alarm command was sent. Lock your phone and swipe to the watch tile to fetch ADT status."
            : "The ADT system could not be saved. Check live status and try again.");
        updateButtons();
    }

    private boolean freshCandidate() {
        long now = SystemClock.elapsedRealtime();
        return candidate != null && now >= candidateStarted && now - candidateStarted < CHOICE_MS;
    }
    private boolean interactive() {
        KeyguardManager lock = getSystemService(KeyguardManager.class);
        return resumed && !isFinishing() && !isDestroyed() && hasWindowFocus() && lock != null
            && !lock.isKeyguardLocked() && !lock.isDeviceLocked();
    }
    private void updateButtons() {
        if (check != null) check.setEnabled(interactive() && inFlight == null);
        if (use != null) { use.setVisibility(candidate == null ? View.GONE : View.VISIBLE);
            use.setEnabled(interactive() && inFlight == null && freshCandidate()); }
    }
    private void cancelQuery() {
        generation++;
        FutureTask<Void> old = inFlight; inFlight = null;
        if (old != null) old.cancel(true);
    }
    private void pageChanged(boolean blocked) {
        if (isDestroyed()) return;
        boolean checking = inFlight != null || candidate != null;
        cancelQuery(); candidate = null;
        if (blocked) status.setText("An unsupported navigation was blocked. Continue signing in on the ADT page.");
        else if (checking) status.setText("The ADT page changed. Finish signing in, then tap Check live status again.");
        updateButtons();
    }

    static boolean allowedNavigation(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443) && host != null
                && ("smartservices.adt.co.uk".equalsIgnoreCase(host) || "www.alarm.com".equalsIgnoreCase(host)
                    || "alarm.com".equalsIgnoreCase(host));
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private final class PortalNavigation extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return blockIfUnexpected(request.getUrl().toString());
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return blockIfUnexpected(url); }
        private boolean blockIfUnexpected(String url) {
            if (allowedNavigation(url)) return false;
            pageChanged(true); return true;
        }
        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            boolean allowed = allowedNavigation(url);
            if (!allowed) view.stopLoading();
            pageChanged(!allowed);
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request.isForMainFrame() && !allowedNavigation(request.getUrl().toString())) {
                handler.post(() -> pageChanged(true));
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", Collections.emptyMap(),
                    new ByteArrayInputStream("Unsupported navigation.".getBytes(StandardCharsets.UTF_8)));
            }
            return null;
        }
        // Keep WebView's default TLS and certificate-error rejection. No JavaScript bridge is installed.
    }

    private static String failureMessage(AdtPortalClient.Status result) {
        if (result == null) return "ADT status is unavailable. Sign in below, then try again.";
        switch (result) {
            case LOGIN_REQUIRED: return "Sign in to ADT below, then tap Check live status.";
            case VERIFY_LOGIN: return "Complete ADT's login verification below, then tap Check live status.";
            case BUSY: return "ADT has not confirmed a settled status. No system was selected. Check the ADT page, then try again.";
            case AMBIGUOUS: return "ADT returned more than one system or partition. No system was selected.";
            case UNSUPPORTED: return "This ADT status response is not supported. No system was selected.";
            default: return "The ADT status request failed. Check code below; signing in again may not be needed.";
        }
    }
    private static String stateLabel(AlarmStateProtocol.State state) {
        return state == AlarmStateProtocol.State.DISARMED ? "Disarmed"
            : state == AlarmStateProtocol.State.ARMED_STAY ? "Armed Stay" : "Armed Away";
    }
    private static String display(String label, String id) {
        if (label == null || label.trim().isEmpty()) return id;
        String clean = label.replaceAll("[\\p{Cntrl}]", " ");
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout column, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(4), 0, dp(4)); column.addView(view); return view;
    }
    private Button button(LinearLayout column, String label, Runnable click) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setMinHeight(dp(44));
        button.setFilterTouchesWhenObscured(true); button.setOnClickListener(view -> click.run()); column.addView(button); return button;
    }
    @Override public void onResume() { super.onResume(); resumed = true; if (web != null) web.onResume(); handler.post(tick); updateButtons(); }
    @Override public void onPause() {
        resumed = false; handler.removeCallbacks(tick);
        if (inFlight != null) recordDiagnostic("UI/CANCELLED", "UNAVAILABLE", Math.max(0, SystemClock.elapsedRealtime() - queryStarted));
        cancelQuery(); candidate = null;
        if (status != null) status.setText("Tap Check live status to read ADT again.");
        updateButtons(); if (web != null) web.onPause(); CookieManager.getInstance().flush(); super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); updateButtons(); }
    @Override public void onDestroy() {
        cancelQuery(); handler.removeCallbacksAndMessages(null);
        if (web != null) { web.stopLoading(); ((LinearLayout) web.getParent()).removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }
}
