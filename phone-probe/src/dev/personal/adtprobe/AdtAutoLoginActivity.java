package dev.personal.adtprobe;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

/** Explicit local credential opt-in; its test performs sign-in and status reads only. */
public final class AdtAutoLoginActivity extends Activity {
    private static final long TEST_MS = 20_000;
    interface Operations {
        boolean configured(Context context);
        boolean save(Context context, String username, char[] password);
        void clear(Context context);
        Result test(Context context, long deadline);
        void cancelTest();
        String describe(Context context);
    }
    static final class Result {
        final boolean ready;
        final String message;
        Result(boolean ready, String message) { this.ready = ready; this.message = message; }
    }
    static Operations operations = new Operations() {
        @Override public boolean configured(Context context) { return AdtCredentialStore.configured(context); }
        @Override public boolean save(Context context, String username, char[] password) {
            return AdtCredentialStore.save(context, username, password);
        }
        @Override public void clear(Context context) { AdtCredentialStore.clear(context); }
        @Override public Result test(Context context, long deadline) {
            AdtSessionRecovery.Check result = AdtSessionRecovery.test(context, deadline);
            return result == null ? null : new Result(result.ready, result.message);
        }
        @Override public void cancelTest() { AdtSessionRecovery.cancelTest(); }
        @Override public String describe(Context context) { return AdtSessionRecovery.describe(context); }
    };
    static Executor testExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "adt-login-test"); thread.setDaemon(true); return thread;
    });
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText username, password;
    private TextView status;
    private Button save, test, forget, manual;
    private FutureTask<Void> inFlight;
    private Operations activeOperations;
    private long deadline;
    private int generation;
    private boolean resumed;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        scroll.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        scroll.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        scroll.setSaveEnabled(false);
        scroll.setBackgroundColor(Color.rgb(17, 22, 30));
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(12), dp(20), dp(20)); scroll.addView(column); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            scroll.setPadding(edges.left, edges.top, edges.right, edges.bottom); return insets;
        });
        text(column, "Automatic ADT sign-in", 24);
        text(column, "Optional: save your ADT username and password encrypted on this phone so ADT Watch can sign in "
            + "again when its website session expires. Background status checks may use them while your phone is locked. "
            + "This is separate from the ADT app. ADT may still require a verification code or manual sign-in.", 15);
        text(column, "Saving and testing sends no alarm command. Saved details are never shown here.", 15);
        String host = sessionHostNote();
        if (host != null) text(column, host, 15);
        username = input(column, "ADT username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = input(column, "ADT password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        save = button(column, "Save and test automatic login", this::saveAndTest);
        test = button(column, "Test saved login", this::beginTest);
        forget = button(column, "Forget saved login", this::forgetLogin);
        manual = button(column, "Open ADT sign-in", this::openManual);
        status = text(column, !bound() ? "First open ADT sign-in and select your ADT system, then return here."
            : configured() ? savedSummary()
            : "Enter your ADT username and password to enable automatic sign-in.", 15);
        updateButtons();
    }

    private void saveAndTest() {
        if (!interactive() || !bound() || inFlight != null) return;
        String name = username.getText().toString().trim();
        if (name.isEmpty() || password.length() == 0) {
            status.setText("Enter both your ADT username and password."); return;
        }
        char[] secret = new char[password.length()];
        password.getText().getChars(0, secret.length, secret, 0);
        boolean saved = false;
        try { saved = operations.save(getApplicationContext(), name, secret); }
        catch (RuntimeException ignored) { /* Never display credential or exception contents. */ }
        finally { Arrays.fill(secret, '\0'); clearInputs(); }
        if (!saved) { status.setText("The login could not be saved securely. Try again."); updateButtons(); return; }
        beginTest();
    }

    private void beginTest() {
        if (!interactive() || !bound() || !configured() || inFlight != null) return;
        clearInputs();
        final int ticket = ++generation;
        final long expires = SystemClock.elapsedRealtime() + TEST_MS;
        final Operations owner = operations;
        final Context app = getApplicationContext();
        activeOperations = owner; deadline = expires;
        status.setText("Testing saved ADT login and reading status…");
        FutureTask<Void> work = new FutureTask<>(() -> {
            Result result;
            try { result = owner.test(app, expires); }
            catch (RuntimeException ignored) { result = null; }
            final Result answer = result;
            handler.post(() -> complete(ticket, answer)); return null;
        });
        inFlight = work; updateButtons();
        handler.postDelayed(() -> {
            if (ticket != generation || inFlight == null) return;
            cancelTest();
            status.setText("The test timed out. The login remains saved. Try again or open ADT sign-in."); updateButtons();
        }, TEST_MS);
        try { testExecutor.execute(work); }
        catch (RuntimeException ignored) { complete(ticket, null); }
    }

    private void complete(int ticket, Result result) {
        if (ticket != generation || inFlight == null) return;
        if (!interactive() || SystemClock.elapsedRealtime() >= deadline) {
            cancelTest(); status.setText("The test ended without a current result. Tap Test saved login again.");
        } else {
            inFlight = null; activeOperations = null;
            status.setText(result == null || result.message == null || result.message.isEmpty()
                ? "The test could not finish. Try again or open ADT sign-in."
                : result.message + (result.ready ? " ADT may still ask you to verify a future sign-in." : ""));
        }
        updateButtons();
    }

    private void forgetLogin() {
        if (!interactive()) return;
        cancelTest(); clearInputs();
        try {
            operations.clear(getApplicationContext());
            status.setText(operations.configured(getApplicationContext())
                ? "The saved login could not be removed. Try again."
                : "Saved login removed. Automatic sign-in is disabled. Your selected ADT system is unchanged.");
        } catch (RuntimeException ignored) { status.setText("The saved login could not be removed. Try again."); }
        updateButtons();
    }

    private void openManual() {
        if (!interactive()) return;
        cancelTest(); clearInputs();
        startActivity(new Intent(this, AdtPortalSetupActivity.class));
    }

    private void cancelTest() {
        ++generation;
        FutureTask<Void> old = inFlight; inFlight = null;
        Operations owner = activeOperations; activeOperations = null;
        if (old != null) old.cancel(true);
        if (owner != null) try { owner.cancelTest(); } catch (RuntimeException ignored) { }
    }
    private void clearInputs() {
        if (username != null) username.getText().clear();
        if (password != null) password.getText().clear();
    }
    private boolean bound() { return AdtPortalSession.binding(this) != null; }
    /** Opening the screen says whether the saved login is verified, paused (and why) or waiting to retry. */
    private String savedSummary() {
        String note = null;
        try { note = operations.describe(getApplicationContext()); } catch (RuntimeException ignored) { }
        return (note == null ? "A login is saved." : note) + " Tap Test saved login to check it.";
    }
    private boolean configured() {
        try { return operations.configured(getApplicationContext()); }
        catch (RuntimeException ignored) { return false; }
    }
    private boolean interactive() {
        return resumed && !isFinishing() && !isDestroyed() && hasWindowFocus() && phoneUnlocked();
    }
    private boolean phoneUnlocked() {
        KeyguardManager lock = getSystemService(KeyguardManager.class);
        return lock != null && !lock.isKeyguardLocked() && !lock.isDeviceLocked();
    }
    /** Login is fixed to www.alarm.com; a session verified on the ADT host cannot lend it a trusted-device cookie. */
    private String sessionHostNote() {
        String verified = AdtPortalSession.verifiedOrigin(this);
        if (verified == null || AdtLoginClient.ORIGIN.equals(verified)) return null;
        return "Your last verified ADT session was on " + URI.create(verified).getHost() + ". Automatic login signs in at "
            + "www.alarm.com only, so it cannot reuse that host's trusted-device cookie and may stop at ADT verification. "
            + "Save and test automatic login shows whether it works for your account before anything relies on it.";
    }
    private void updateButtons() {
        if (save == null) return;
        boolean active = interactive(), idle = inFlight == null, setup = bound();
        save.setEnabled(active && idle && setup); test.setEnabled(active && idle && setup && configured());
        forget.setEnabled(active); manual.setEnabled(active);
        // IME/other transient windows can change window focus while this Activity is still
        // resumed. Disabling an EditText here removes its focus and immediately hides the IME.
        boolean editable = resumed && !isFinishing() && !isDestroyed() && phoneUnlocked() && idle && setup;
        username.setEnabled(editable); password.setEnabled(editable);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout column, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(6), 0, dp(6)); column.addView(view); return view;
    }
    private EditText input(LinearLayout column, String hint, int type) {
        EditText view = new EditText(this); view.setHint(hint); view.setInputType(type); view.setSingleLine(true);
        view.setTextColor(Color.WHITE); view.setHintTextColor(Color.LTGRAY); view.setSaveEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        view.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        view.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        view.setFilterTouchesWhenObscured(true); column.addView(view); return view;
    }
    private Button button(LinearLayout column, String label, Runnable click) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setMinHeight(dp(48));
        view.setFilterTouchesWhenObscured(true); view.setOnClickListener(ignored -> click.run()); column.addView(view); return view;
    }
    @Override public void onResume() { super.onResume(); resumed = true; updateButtons(); }
    @Override public void onPause() {
        resumed = false;
        if (inFlight != null) status.setText("Test cancelled. Tap Test saved login when you return.");
        cancelTest(); clearInputs(); updateButtons(); super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) {
            if (inFlight != null) status.setText("Test cancelled. Tap Test saved login again.");
            cancelTest();
        }
        updateButtons();
    }
    @Override public void onDestroy() {
        cancelTest(); clearInputs(); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
