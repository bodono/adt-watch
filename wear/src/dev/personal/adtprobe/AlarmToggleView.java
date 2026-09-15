package dev.personal.adtprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Consumer;

/** Presentation only: the owner supplies a verified state/action and owns all transport. */
final class AlarmToggleView extends ScrollView {
    static final int ARMED_RED = Color.rgb(174, 38, 48);
    static final int DISARMED_GREEN = Color.rgb(26, 112, 69);
    static final int UNKNOWN_GREY = Color.rgb(57, 62, 69);
    final LinearLayout content;
    final TextView titleView;
    final TextView statusView;
    final Button alarmButton;
    final Button refreshButton;
    private final Consumer<AlarmAction> activate;
    private AlarmAction renderedAction;

    AlarmToggleView(Context context, Consumer<AlarmAction> activate, Runnable refresh) {
        super(context);
        this.activate = activate;
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        setBackgroundColor(Color.BLACK);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(0, dp(16), 0, dp(16));
        addView(content, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleView = text("ADT Watch", 18, Color.WHITE);
        add(titleView, 112, ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        statusView = text("", 14, Color.LTGRAY);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        add(statusView, 164, Math.max(dp(22), statusView.getLineHeight()), 0);

        alarmButton = new Button(context);
        alarmButton.setAllCaps(false);
        alarmButton.setIncludeFontPadding(false);
        alarmButton.setTextColor(Color.WHITE);
        alarmButton.setGravity(Gravity.CENTER);
        alarmButton.setMaxLines(5);
        alarmButton.setEllipsize(TextUtils.TruncateAt.END);
        alarmButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        alarmButton.setBackgroundTintList(null);
        int diameterDp = getResources().getConfiguration().fontScale > 1.3f ? 192 : 128;
        add(alarmButton, diameterDp, dp(diameterDp), 0);

        FrameLayout footer = new FrameLayout(context);
        refreshButton = new Button(context);
        refreshButton.setText("Refresh");
        refreshButton.setAllCaps(false);
        refreshButton.setTextSize(12);
        refreshButton.setIncludeFontPadding(false);
        refreshButton.setTextColor(Color.rgb(174, 203, 250));
        refreshButton.setPadding(dp(4), dp(4), dp(4), dp(4));
        refreshButton.setMinHeight(0);
        refreshButton.setMinimumHeight(0);
        refreshButton.setBackgroundTintList(null);
        refreshButton.setBackgroundColor(Color.TRANSPARENT);
        refreshButton.setContentDescription("Refresh alarm status");
        refreshButton.setOnClickListener(view -> refresh.run());
        footer.addView(refreshButton, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        add(footer, 112, Math.max(dp(32), refreshButton.getLineHeight() + dp(8)), 4);
        render("Status unknown", null, false, "Refresh to check the alarm.");
    }

    void render(String statusText, AlarmAction action, boolean enabled, String detail) {
        String state = statusText == null ? "Status unknown" : statusText;
        String explanation = detail == null ? "" : detail;
        boolean available = action != null && enabled;
        if (renderedAction != action || alarmButton.isEnabled() != available) cancelAlarmGesture();
        renderedAction = action;
        statusView.setText(state);
        alarmButton.setEnabled(available);
        alarmButton.setTextSize(action == null ? 20 : 22);
        boolean working = action == null && ("Sending request".equals(state) || "Checking alarm".equals(state));
        String label = action == null ? working ? "Working…" : "Unknown" : action.label();
        SpannableString text = new SpannableString(label + (explanation.isEmpty() ? "" : "\n" + explanation));
        int detailSize = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12,
            getResources().getDisplayMetrics()));
        if (!explanation.isEmpty()) text.setSpan(new AbsoluteSizeSpan(detailSize), label.length() + 1,
            text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        alarmButton.setText(text);
        alarmButton.setStateDescription(state);
        alarmButton.setContentDescription(state + ". " + (action == null ? "Alarm control unavailable" : label)
            + (explanation.isEmpty() ? "." : ". " + explanation));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(action == AlarmAction.DISARM ? ARMED_RED
            : action == AlarmAction.ARM_STAY ? DISARMED_GREEN : UNKNOWN_GREY);
        alarmButton.setBackground(circle);
        // Capture the rendered action, never derive a command from label text or color.
        alarmButton.setOnClickListener(view -> {
            if (!alarmButton.isEnabled() || action == null || renderedAction != action) return;
            alarmButton.setEnabled(false); // Consume this input before calling code that may re-render.
            activate.accept(action);
        });
        refreshButton.setVisibility(action == null ? View.VISIBLE : View.INVISIBLE);
        refreshButton.setEnabled(action == null);
    }

    private void cancelAlarmGesture() {
        // A state change must not turn a finger already down (or its queued click) into another action.
        alarmButton.cancelPendingInputEvents();
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        alarmButton.onTouchEvent(cancel);
        cancel.recycle();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    private void add(View view, float widthDp, int heightPx, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), heightPx);
        params.topMargin = dp(marginDp);
        content.addView(view, params);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
