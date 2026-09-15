package dev.personal.adtprobe;

import android.content.Context;
import android.graphics.Color;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.protolayout.material.Button;
import androidx.wear.protolayout.material.ButtonColors;
import androidx.wear.tiles.EventBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;

/**
 * Passive alarm display and a native one-tap launch into the watch activity.
 *
 * The service never sends an alarm message. WatchActivity must consume the opaque
 * revision token once, revalidate the current action/state and its own unlocked,
 * visible user context, and abandon stale launches. REFRESH never authorizes an
 * alarm action, including when a later refresh discovers a known state.
 *
 * API references: developer.android.com/training/wearables/tiles/interactions
 * and developer.android.com/jetpack/androidx/releases/wear-tiles.
 */
public final class AlarmTileService extends TileService {
    public static final String EXTRA_TILE_ACTION = "dev.personal.adtprobe.tile.ACTION";
    public static final String EXTRA_TILE_REVISION = "dev.personal.adtprobe.tile.REVISION";
    public static final String ACTION_REFRESH = "REFRESH";
    private static final String RESOURCES_VERSION = "alarm-tile-1";
    private static final int ARM_GREEN = 0xff237a45;
    private static final int DISARM_RED = 0xffb3261e;
    private static final int UNKNOWN_GREY = 0xff42464d;

    /** Call after a stored state changes. Delivery and rendering are platform scheduled. */
    public static void requestUpdate(Context context) {
        TileService.getUpdater(context.getApplicationContext()).requestUpdate(AlarmTileService.class);
    }

    @Override protected ListenableFuture<Void> onRecentInteractionEventsAsync(
            List<EventBuilders.TileInteractionEvent> events) {
        for (EventBuilders.TileInteractionEvent event : events) {
            if (event.getEventType() == EventBuilders.TileInteractionEvent.ENTER) {
                WatchAlarmStore.refresh(this);
                break;
            }
        }
        return immediate(null);
    }

    /** Older renderers use this callback; neither callback can authorize an alarm action. */
    @SuppressWarnings("deprecation")
    @Override protected void onTileEnterEvent(EventBuilders.TileEnterEvent event) {
        WatchAlarmStore.refresh(this);
    }

    @Override protected ListenableFuture<TileBuilders.Tile> onTileRequest(
            RequestBuilders.TileRequest request) {
        WatchAlarmStore.ViewState state = WatchAlarmStore.read(this);
        int screenWidth = request.getDeviceConfiguration().getScreenWidthDp();
        int screenHeight = request.getDeviceConfiguration().getScreenHeightDp();
        float edge = Math.min(screenWidth > 0 ? screenWidth : 192,
                screenHeight > 0 ? screenHeight : 192);
        float diameter = Math.max(72f, Math.min(120f, edge - 88f));
        float textWidth = Math.max(120f, edge * 0.76f);

        AlarmAction action = actionable(state) ? state.action : null;
        String buttonLabel = action == null ? "Refresh" : action.label();
        int color = action == AlarmAction.DISARM ? DISARM_RED
                : action == AlarmAction.ARM_STAY ? ARM_GREEN : UNKNOWN_GREY;
        String label = state == null ? "State unknown" : safeText(state.label, "State unknown");
        String detail = state == null ? "Tap to check your alarm" : safeText(state.detail, "Tap to check your alarm");

        ModifiersBuilders.Clickable click = new ModifiersBuilders.Clickable.Builder()
                .setId("alarm-control")
                .setOnClick(launchAction(state))
                .build();
        Button button = new Button.Builder(this, click)
                .setSize(DimensionBuilders.dp(diameter))
                .setButtonColors(new ButtonColors(color, Color.WHITE))
                .setCustomContent(text(buttonLabel, 20, Color.WHITE, 2))
                .setContentDescription(buttonLabel + ". " + label)
                .build();
        LayoutElementBuilders.Column content = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.dp(textWidth))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text(label, 16, Color.WHITE, 1))
                .addContent(spacer(6))
                .addContent(button)
                .addContent(spacer(6))
                .addContent(text(detail, 12, 0xffc4c7c5, 2))
                .build();
        LayoutElementBuilders.Box root = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(Color.BLACK)).build()).build())
                .addContent(content)
                .build();
        TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                // Refresh is best effort; it is never the security expiry check.
                .setFreshnessIntervalMillis(60_000)
                .setTileTimeline(new TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(new LayoutElementBuilders.Layout.Builder().setRoot(root).build())
                                .build()).build())
                .build();
        return immediate(tile);
    }

    @Override protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            RequestBuilders.ResourcesRequest request) {
        return immediate(new ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build());
    }

    /** Kept separate so offline tests can inspect every outgoing launch field. */
    static ActionBuilders.LaunchAction launchAction(WatchAlarmStore.ViewState state) {
        boolean actionable = actionable(state);
        ActionBuilders.AndroidActivity.Builder activity = new ActionBuilders.AndroidActivity.Builder()
                .setPackageName("dev.personal.adtprobe")
                .setClassName("dev.personal.adtprobe.WatchActivity")
                .addKeyToExtraMapping(EXTRA_TILE_ACTION, new ActionBuilders.AndroidStringExtra.Builder()
                        .setValue(actionable ? state.action.name() : ACTION_REFRESH).build());
        if (actionable) {
            activity.addKeyToExtraMapping(EXTRA_TILE_REVISION, new ActionBuilders.AndroidStringExtra.Builder()
                    .setValue(state.revision).build());
        }
        return new ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build();
    }

    private static boolean actionable(WatchAlarmStore.ViewState state) {
        return state != null && state.enabled && state.action != null
                && state.revision != null && !state.revision.isEmpty();
    }

    private static LayoutElementBuilders.Text text(String value, int sp, int color, int maxLines) {
        return new LayoutElementBuilders.Text.Builder().setText(value)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(sp)).setColor(ColorBuilders.argb(color)).build())
                .setMaxLines(maxLines)
                .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                .build();
    }

    private static LayoutElementBuilders.Spacer spacer(float height) {
        return new LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(height)).build();
    }

    private static String safeText(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static <T> ListenableFuture<T> immediate(T value) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            completer.set(value);
            return "Alarm tile snapshot";
        });
    }
}
