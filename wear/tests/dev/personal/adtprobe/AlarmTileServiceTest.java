package dev.personal.adtprobe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.wear.tiles.EventBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import com.google.common.util.concurrent.ListenableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real tile lifecycle with an inert status-only transport; no alarm sender exists. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AlarmTileServiceTest {
    private static final int BOOT = 24;
    private static final String PHONE = "inert-tile-phone";
    private static final String REVISION = "11111111-1111-1111-1111-111111111111";
    private ServiceController<AlarmTileService> controller;
    private AlarmTileService service;
    private WatchAlarmStore.StatusTransport original;
    private FakeTransport transport;

    @Before public void prepare() {
        reset();
        controller = Robolectric.buildService(AlarmTileService.class).create();
        service = controller.get();
        Settings.Global.putInt(service.getContentResolver(), Settings.Global.BOOT_COUNT, BOOT);
        service.getSharedPreferences(WatchAlarmStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        original = ReflectionHelpers.getStaticField(WatchAlarmStore.class, "transport");
        transport = new FakeTransport();
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "transport", transport);
    }

    @After public void close() {
        if (controller != null) controller.destroy();
        reset();
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "transport", original);
    }

    private void reset() {
        ((Handler) ReflectionHelpers.getStaticField(WatchAlarmStore.class, "MAIN")).removeCallbacksAndMessages(null);
        ReflectionHelpers.callStaticMethod(WatchAlarmStore.class, "cancelRecovery");
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "lastRefresh", -1L);
        ReflectionHelpers.setStaticField(WatchAlarmStore.class, "activeSelection", null);
    }

    @Test public void rendererRequestWithoutEnterEventAutomaticallyFetchesAndReturnsFreshTile() throws Exception {
        ListenableFuture<TileBuilders.Tile> preview = request();
        assertTrue("Visible checking frame does not wait for the phone", preview.isDone());
        assertTrue(tileText(preview.get()).contains("Checking"));
        ListenableFuture<TileBuilders.Tile> result = request();
        idle();
        assertFalse(result.isDone());
        assertEquals(1, transport.nonces.size());
        reply(0, 0);
        advance(100);
        assertTrue(result.isDone());
        assertTrue(tileText(result.get()).contains("Arm Stay"));
        assertEquals(AlarmAction.ARM_STAY, WatchAlarmStore.read(service).action);
        assertNull("Loading a tile cannot select an alarm action",
                ReflectionHelpers.getStaticField(WatchAlarmStore.class, "activeSelection"));
        for (int i = 0; i < 10; i++) assertTrue(request().isDone());
        idle();
        assertEquals("Store-driven tile redraws do not start a query loop", 1, transport.nonces.size());
    }

    @Test public void oldCachedStatusIsFetchedWithoutARefreshTap() throws Exception {
        request(); idle(); reply(0, 0); advance(100);
        advance(61_000);
        assertFalse(WatchAlarmStore.read(service).enabled);
        assertTrue(request().isDone());
        ListenableFuture<TileBuilders.Tile> result = request(); idle();
        assertFalse(result.isDone());
        assertEquals(2, transport.nonces.size());
        reply(1, 61_100); advance(100);
        assertTrue(tileText(result.get()).contains("Arm Stay"));
        assertEquals(60_000, result.get().getFreshnessIntervalMillis());
    }

    @Test public void unansweredQueryReturnsWithinThePlatformDeadlineAndCannotLoopOnRedraw() throws Exception {
        assertTrue(request().isDone());
        ListenableFuture<TileBuilders.Tile> result = request(); idle();
        advance(7_999); assertFalse(result.isDone());
        advance(1); assertTrue(result.isDone());
        assertTrue(tileText(result.get()).contains("Checking"));
        assertFalse(WatchAlarmStore.read(service).enabled);
        advance(22_000);
        assertFalse(WatchAlarmStore.isRefreshing());
        int count = transport.nonces.size();
        for (int i = 0; i < 10; i++) assertTrue(request().isDone());
        idle();
        assertEquals(count, transport.nonces.size());
        assertTrue(tileText(request().get()).contains("Refresh"));
    }

    @Test public void cancellationAndServiceDestructionDoNotCancelSharedStatusRecovery() {
        assertTrue(request().isDone());
        ListenableFuture<TileBuilders.Tile> cancelled = request();
        ListenableFuture<TileBuilders.Tile> surviving = request(); idle();
        cancelled.cancel(false); idle();
        assertTrue(WatchAlarmStore.isRefreshing());
        assertFalse(surviving.isDone());
        controller.destroy(); controller = null;
        assertTrue(surviving.isDone());
        assertTrue(cancelled.isCancelled());
        assertTrue(WatchAlarmStore.isRefreshing());
        assertTrue(((List<?>) ReflectionHelpers.getField(service, "waits")).isEmpty());
        reply(0, 0); advance(100);
        assertTrue(WatchAlarmStore.read(service).enabled);
    }

    @Test public void rebindingServiceCannotRepeatThePreviewOrStartAnotherQuery() throws Exception {
        assertTrue(request().isDone()); idle();
        controller.destroy();
        controller = Robolectric.buildService(AlarmTileService.class).create();
        service = controller.get();
        ListenableFuture<TileBuilders.Tile> result = request(); idle();
        assertFalse("The recovery owns the preview claim across service instances", result.isDone());
        assertEquals(1, transport.nonces.size());
        reply(0, 0); advance(100);
        assertTrue(tileText(result.get()).contains("Arm Stay"));
    }

    @Test public void modernEnterCallbackWaitsForStatusAndCoalescesWithTileRequest() throws Exception {
        EventBuilders.TileInteractionEvent enter = new EventBuilders.TileInteractionEvent.Builder(
                1, EventBuilders.TileInteractionEvent.ENTER).build();
        ListenableFuture<Void> entered = service.onRecentInteractionEventsAsync(Collections.singletonList(enter));
        idle();
        ListenableFuture<TileBuilders.Tile> tile = request(); idle();
        assertEquals(1, transport.nonces.size());
        assertFalse(entered.isDone());
        reply(0, 0); advance(100);
        assertTrue(entered.isDone());
        assertTrue(tile.isDone());
        assertNull(entered.get());
    }

    private String tileText(TileBuilders.Tile tile) {
        return text(tile.getTileTimeline().getTimelineEntries().get(0).getLayout().getRoot());
    }
    private String text(LayoutElementBuilders.LayoutElement element) {
        if (element instanceof LayoutElementBuilders.Text)
            return ((LayoutElementBuilders.Text) element).getText().getValue();
        List<LayoutElementBuilders.LayoutElement> children = Collections.emptyList();
        if (element instanceof LayoutElementBuilders.Box) children = ((LayoutElementBuilders.Box) element).getContents();
        if (element instanceof LayoutElementBuilders.Column) children = ((LayoutElementBuilders.Column) element).getContents();
        if (element instanceof LayoutElementBuilders.Row) children = ((LayoutElementBuilders.Row) element).getContents();
        StringBuilder result = new StringBuilder();
        for (LayoutElementBuilders.LayoutElement child : children) result.append(text(child)).append(' ');
        return result.toString();
    }
    private ListenableFuture<TileBuilders.Tile> request() {
        return service.onTileRequest(new RequestBuilders.TileRequest.Builder().build());
    }
    private void reply(int index, long age) {
        assertTrue(WatchAlarmStore.accept(service, PHONE, new AlarmStateProtocol.Report(
                transport.nonces.get(index), AlarmStateProtocol.State.DISARMED,
                AlarmStateProtocol.Availability.READY, REVISION, age), SystemClock.elapsedRealtime(), BOOT));
    }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void advance(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }

    private static final class FakeTransport implements WatchAlarmStore.StatusTransport {
        final List<String> nonces = new ArrayList<>();
        @Override public void discover(Context context, Consumer<String> found, Runnable failed) { found.accept(PHONE); }
        @Override public void query(Context context, String phone, String nonce, Runnable failed) { nonces.add(nonce); }
    }
}
