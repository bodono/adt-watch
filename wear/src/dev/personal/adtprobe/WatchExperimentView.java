package dev.personal.adtprobe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.Consumer;

/** Presentation only. The activity owns readiness, confirmation and all transport. */
final class WatchExperimentView extends ScrollView {
    final LinearLayout content;
    final TextView titleView;
    final TextView feedbackView;
    final Button armStayButton;
    final Button disarmButton;
    final Button confirmButton;
    final Button connectionButton;

    WatchExperimentView(Context context, Consumer<AlarmAction> start, Runnable confirm, Runnable connection) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setFillViewport(true);
        setClipToPadding(false);
        setVerticalScrollBarEnabled(false);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(0, dp(16), 0, dp(16));
        addView(content, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        titleView = new TextView(context);
        titleView.setText("ADT Watch");
        titleView.setTextSize(18);
        titleView.setTextColor(Color.WHITE);
        titleView.setGravity(Gravity.CENTER);
        titleView.setIncludeFontPadding(false);
        add(titleView, 112, 0);

        feedbackView = new TextView(context);
        feedbackView.setTextSize(12);
        feedbackView.setTextColor(Color.rgb(218, 222, 228));
        feedbackView.setGravity(Gravity.CENTER);
        feedbackView.setIncludeFontPadding(false);
        // Reserve the same space for every status at a given font scale. Larger type
        // needs more wrapping lines; the ScrollView keeps those lines reachable.
        int reservedLines = Math.max(3, (int) Math.ceil(
            3 * getResources().getConfiguration().fontScale));
        feedbackView.setLines(reservedLines);
        feedbackView.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        add(feedbackView, 172, 0);
        ViewGroup.LayoutParams feedbackBounds = feedbackView.getLayoutParams();
        feedbackBounds.height = Math.max(dp(44), feedbackView.getLineHeight() * reservedLines
            + feedbackView.getCompoundPaddingTop() + feedbackView.getCompoundPaddingBottom());
        feedbackView.setLayoutParams(feedbackBounds);

        armStayButton = action(AlarmAction.ARM_STAY.label(), () -> start.accept(AlarmAction.ARM_STAY), 48);
        armStayButton.setContentDescription("Start Arm Stay request");
        disarmButton = action(AlarmAction.DISARM.label(), () -> start.accept(AlarmAction.DISARM), 48);
        disarmButton.setContentDescription("Start Disarm request");
        LinearLayout actions = new LinearLayout(context);
        boolean largeType = getResources().getConfiguration().fontScale > 1.3f;
        actions.setOrientation(largeType ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams armBounds = new LinearLayout.LayoutParams(
            largeType ? ViewGroup.LayoutParams.MATCH_PARENT : dp(88), ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams disarmBounds = new LinearLayout.LayoutParams(
            largeType ? ViewGroup.LayoutParams.MATCH_PARENT : dp(88), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (largeType) disarmBounds.topMargin = dp(4);
        else disarmBounds.leftMargin = dp(8);
        actions.addView(armStayButton, armBounds);
        actions.addView(disarmButton, disarmBounds);
        add(actions, 184, 0);
        confirmButton = action("Confirm Arm Stay", confirm, 48);
        add(confirmButton, 176, 4);
        ViewGroup.LayoutParams confirmBounds = confirmButton.getLayoutParams();
        confirmBounds.height = Math.max(dp(48), 2 * confirmButton.getLineHeight()
            + confirmButton.getCompoundPaddingTop() + confirmButton.getCompoundPaddingBottom());
        confirmButton.setLayoutParams(confirmBounds);
        connectionButton = action("Connection check", connection, 32);
        connectionButton.setTextSize(12);
        connectionButton.setPadding(dp(2), 0, dp(2), 0);
        connectionButton.setBackgroundTintList(null);
        connectionButton.setBackgroundColor(Color.TRANSPARENT);
        connectionButton.setTextColor(Color.rgb(174, 203, 250));
        add(connectionButton, 128, 4);
        render(null, false, false, "Choose Disarm or Arm Stay.");
    }

    void render(AlarmAction activeAction, boolean canStart, boolean canConfirm, String feedback) {
        // A running attempt owns its action. Selection never changes an existing attempt.
        armStayButton.setEnabled(canStart && activeAction == null);
        disarmButton.setEnabled(canStart && activeAction == null);
        confirmButton.setEnabled(activeAction != null && canConfirm);
        String confirmLabel = activeAction == null ? "Confirm action" : "Confirm " + activeAction.label();
        if (!confirmLabel.contentEquals(confirmButton.getText())) confirmButton.setText(confirmLabel);
        if (!feedbackView.getText().toString().equals(feedback)) feedbackView.setText(feedback);
    }

    private Button action(String text, Runnable callback, int minimumHeight) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setIncludeFontPadding(false);
        button.setMaxLines(2);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(minimumHeight));
        button.setMinimumHeight(dp(minimumHeight));
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24));
        button.setBackground(background);
        int[][] states = {new int[] {android.R.attr.state_enabled}, new int[0]};
        button.setBackgroundTintList(new ColorStateList(states,
            new int[] {Color.rgb(138, 180, 248), Color.rgb(48, 52, 58)}));
        button.setTextColor(new ColorStateList(states,
            new int[] {Color.rgb(12, 24, 42), Color.rgb(160, 165, 172)}));
        button.setOnClickListener(view -> callback.run());
        return button;
    }

    private void add(android.view.View view, int width, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            dp(width), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        content.addView(view, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
