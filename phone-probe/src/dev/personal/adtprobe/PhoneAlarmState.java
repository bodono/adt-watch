package dev.personal.adtprobe;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.service.notification.StatusBarNotification;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Latest exact ADT panel report, never an inferred result of sending a scene. */
final class PhoneAlarmState {
    static final String ADT_PACKAGE = "com.adtuk.adtukalarm";
    static final String PREFERENCES = "reported_alarm_state";
    static final long MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L;
    /** ADT writes the human-readable event time in the account's UK local time, whatever zone the phone is in. */
    static final ZoneId ADT_ZONE = ZoneId.of("Europe/London");
    private static final Pattern TITLE = Pattern.compile("^([^\\r\\n()]+) (Disarmed|Armed Stay|Armed Away) \\(([^\\r\\n()]+)\\)$");
    private static final Pattern BODY = Pattern.compile("^([^\\r\\n]+?): ([^\\r\\n()]+) was (Disarmed|Armed Stay|Armed Away) at ([0-9]{2}:[0-9]{2}) on ([0-9]{2}/[0-9]{2}/[0-9]{4})\\. \\(([^\\r\\n()]+)\\)$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm", Locale.UK)
        .withResolverStyle(ResolverStyle.STRICT);
    private static boolean connected, reconciled, connectionHasLatest, storageFailed;

    private PhoneAlarmState() { }

    static final class Snapshot {
        final AlarmStateProtocol.State state;
        final String revision;
        final long ageMillis;
        final AlarmStateProtocol.Availability availability;
        Snapshot(AlarmStateProtocol.State state, String revision, long ageMillis, AlarmStateProtocol.Availability availability) {
            this.state = state; this.revision = revision; this.ageMillis = ageMillis; this.availability = availability;
        }
    }

    static synchronized Snapshot snapshot(Context context) {
        Ledger ledger = read(context);
        long age = ledger.eventMillis > 0 ? Math.max(0, System.currentTimeMillis() - ledger.eventMillis) : 0;
        AlarmStateProtocol.Availability availability;
        if (!permissionGranted(context)) availability = AlarmStateProtocol.Availability.NO_ACCESS;
        else if (!validAdt(context)) availability = AlarmStateProtocol.Availability.SETUP;
        else if (!connected || !reconciled) availability = AlarmStateProtocol.Availability.OFFLINE;
        else if (storageFailed) availability = AlarmStateProtocol.Availability.NO_STATE;
        else availability = ledger.availability(System.currentTimeMillis(), connectionHasLatest);
        return new Snapshot(availability == AlarmStateProtocol.Availability.READY ? ledger.state : AlarmStateProtocol.State.UNKNOWN,
            ledger.revision, age, availability);
    }

    static synchronized boolean matches(Context context, String revision, AlarmAction action) {
        Snapshot current = snapshot(context);
        return current.availability == AlarmStateProtocol.Availability.READY && current.revision.equals(revision)
            && actionFor(current.state) == action;
    }

    /** Consumes the displayed revision durably before any native scene activation. */
    static synchronized boolean beginCommand(Context context, String revision, AlarmAction action) {
        if (!matches(context, revision, action)) return false;
        Ledger ledger = read(context);
        ledger.begin(System.currentTimeMillis());
        if (!write(context, ledger)) return false;
        changed(context);
        return true;
    }

    static boolean permissionGranted(Context context) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.isNotificationListenerAccessGranted(new ComponentName(context, AdtStateListener.class));
        } catch (RuntimeException ignored) { return false; }
    }

    static String setupStatus(Context context) {
        Snapshot value = snapshot(context);
        switch (value.availability) {
            case READY: return "ADT last reported " + stateLabel(value.state) + ". State notifications are enabled.";
            case NO_ACCESS: return "Allow notification access to receive ADT alarm state.";
            case SETUP: return "The supported ADT app version is required for state reports.";
            case OFFLINE: return "Waiting for the ADT notification listener to connect.";
            case STALE: return "The latest ADT state report is over 24 hours old. Check ADT.";
            case BUSY: return "Waiting for a newer ADT state report after the last request.";
            default: return "Alarm state is unknown. A clear ADT Armed or Disarmed notification is needed.";
        }
    }

    static synchronized void listenerConnecting(Context context) {
        connected = true; reconciled = false; connectionHasLatest = false;
        changed(context);
    }

    static synchronized void reconcile(Context context, StatusBarNotification[] notifications) {
        if (!connected) return;
        Ledger ledger = read(context);
        long now = System.currentTimeMillis();
        if (notifications != null) for (StatusBarNotification notification : notifications) {
            Event event = parse(notification, now);
            if (event != null) ledger.accept(event);
        }
        connectionHasLatest = false;
        if (notifications != null) for (StatusBarNotification notification : notifications) {
            Event event = parse(notification, now);
            if (event != null && ledger.isLatest(event)) connectionHasLatest = true;
        }
        reconciled = notifications != null;
        write(context, ledger);
        changed(context);
    }

    static synchronized void posted(Context context, StatusBarNotification notification) {
        if (!connected || !reconciled) return;
        Event event = parse(notification, System.currentTimeMillis());
        if (event == null) return;
        Ledger ledger = read(context);
        boolean updated = ledger.accept(event);
        boolean previouslyHadLatest = connectionHasLatest;
        if (ledger.isLatest(event)) connectionHasLatest = true;
        if (updated) write(context, ledger);
        if (updated || previouslyHadLatest != connectionHasLatest) changed(context);
    }

    static synchronized void listenerDisconnected(Context context) {
        connected = false; reconciled = false; connectionHasLatest = false;
        changed(context);
    }

    private static void changed(Context context) {
        // No notification text, account, home, panel or notification identifiers leave this class.
        PhoneStateLink.publish(context.getApplicationContext());
    }

    private static boolean validAdt(Context context) {
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(ADT_PACKAGE, 0);
            return installed.getLongVersionCode() == 2307 && installed.applicationInfo != null && installed.applicationInfo.enabled;
        } catch (Exception ignored) { return false; }
    }

    static AlarmAction actionFor(AlarmStateProtocol.State state) {
        if (state == AlarmStateProtocol.State.DISARMED) return AlarmAction.ARM_STAY;
        if (state == AlarmStateProtocol.State.ARMED_STAY || state == AlarmStateProtocol.State.ARMED_AWAY) return AlarmAction.DISARM;
        return null;
    }

    private static String stateLabel(AlarmStateProtocol.State state) {
        switch (state) {
            case DISARMED: return "Disarmed";
            case ARMED_STAY: return "Armed Stay";
            case ARMED_AWAY: return "Armed Away";
            default: return "unknown";
        }
    }

    static final class Event {
        final AlarmStateProtocol.State state;
        final String scope;
        final long millis;
        Event(AlarmStateProtocol.State state, String scope, long millis) { this.state = state; this.scope = scope; this.millis = millis; }
    }

    static Event parse(StatusBarNotification posted, long now) {
        if (posted == null || !ADT_PACKAGE.equals(posted.getPackageName())) return null;
        try {
            Notification notification = posted.getNotification();
            if (notification == null || notification.extras == null || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
            CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence body = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence big = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            // Both complete text forms must agree; do not guess from a truncated or generic notification.
            if (big != null && body != null && !big.toString().equals(body.toString())) return null;
            return parseText(title == null ? null : title.toString(), body != null ? body.toString() : big == null ? null : big.toString(),
                notification.when, now, ADT_ZONE);
        } catch (RuntimeException ignored) { return null; }
    }

    static Event parseText(String title, String body, long when, long now, ZoneId zone) {
        if (title == null || body == null || title.length() > 512 || body.length() > 2048 || when <= 0 || when > now + 60_000) return null;
        Matcher heading = TITLE.matcher(title), detail = BODY.matcher(body);
        if (!heading.matches() || !detail.matches() || !heading.group(1).equals(detail.group(2))
                || !heading.group(2).equals(detail.group(3)) || !heading.group(3).equals(detail.group(6))) return null;
        try {
            LocalDateTime minute = LocalDateTime.parse(detail.group(5) + " " + detail.group(4), DATE);
            // ADT's second-resolution event timestamp must agree with the human-readable local minute.
            if (!LocalDateTime.ofInstant(Instant.ofEpochMilli(when), zone).withSecond(0).withNano(0).equals(minute)) return null;
            AlarmStateProtocol.State state;
            switch (detail.group(3)) {
                case "Disarmed": state = AlarmStateProtocol.State.DISARMED; break;
                case "Armed Stay": state = AlarmStateProtocol.State.ARMED_STAY; break;
                case "Armed Away": state = AlarmStateProtocol.State.ARMED_AWAY; break;
                default: return null;
            }
            String scope = digest(detail.group(1) + "\n" + detail.group(2) + "\n" + detail.group(6));
            return new Event(state, scope, when);
        } catch (RuntimeException ignored) { return null; }
    }

    private static String digest(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (Exception failure) { throw new IllegalStateException("Scope hashing unavailable"); }
    }

    /** Pure ordering/pending model, also exercised with inert test events. */
    static final class Ledger {
        AlarmStateProtocol.State state = AlarmStateProtocol.State.UNKNOWN;
        String scope = "", revision = UUID.randomUUID().toString();
        long eventMillis, conflictMillis, pendingAfter;
        boolean ambiguous;

        boolean accept(Event event) {
            if (ambiguous) return false;
            if (scope.isEmpty()) scope = event.scope;
            if (!scope.equals(event.scope)) { ambiguous = true; revise(); return true; }
            if (event.millis < eventMillis) return false;
            if (event.millis == eventMillis) {
                if (event.state != state && conflictMillis != eventMillis) { conflictMillis = eventMillis; revise(); return true; }
                return false;
            }
            state = event.state; eventMillis = event.millis; conflictMillis = 0;
            if (pendingAfter > 0 && eventMillis > pendingAfter) pendingAfter = 0;
            revise();
            return true;
        }

        boolean isLatest(Event event) {
            return !ambiguous && conflictMillis == 0 && scope.equals(event.scope) && eventMillis == event.millis && state == event.state;
        }

        void begin(long now) { pendingAfter = Math.max(now, eventMillis); revise(); }

        AlarmStateProtocol.Availability availability(long now, boolean hasLatest) {
            if (ambiguous || conflictMillis > 0) return AlarmStateProtocol.Availability.NO_STATE;
            if (pendingAfter > 0) return AlarmStateProtocol.Availability.BUSY;
            if (!hasLatest || eventMillis <= 0 || state == AlarmStateProtocol.State.UNKNOWN) return AlarmStateProtocol.Availability.NO_STATE;
            if (now < eventMillis || now - eventMillis > MAX_AGE_MILLIS) return AlarmStateProtocol.Availability.STALE;
            return AlarmStateProtocol.Availability.READY;
        }

        private void revise() { revision = UUID.randomUUID().toString(); }
    }

    private static Ledger read(Context context) {
        Ledger ledger = new Ledger();
        try {
            SharedPreferences stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            ledger.state = AlarmStateProtocol.State.valueOf(stored.getString("state", "UNKNOWN"));
            ledger.scope = stored.getString("scope_hash", "");
            ledger.revision = stored.getString("revision", ledger.revision);
            UUID.fromString(ledger.revision);
            ledger.eventMillis = stored.getLong("event_ms", 0);
            ledger.conflictMillis = stored.getLong("conflict_ms", 0);
            ledger.pendingAfter = stored.getLong("pending_after_ms", 0);
            ledger.ambiguous = stored.getBoolean("ambiguous", false);
            if (ledger.eventMillis < 0 || ledger.conflictMillis < 0 || ledger.pendingAfter < 0
                    || (!ledger.scope.isEmpty() && !ledger.scope.matches("[0-9a-f]{64}"))) throw new IllegalStateException();
        } catch (RuntimeException failure) { storageFailed = true; return new Ledger(); }
        return ledger;
    }

    private static boolean write(Context context, Ledger ledger) {
        try {
            boolean saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear()
                .putString("state", ledger.state.name()).putString("scope_hash", ledger.scope).putString("revision", ledger.revision)
                .putLong("event_ms", ledger.eventMillis).putLong("conflict_ms", ledger.conflictMillis)
                .putLong("pending_after_ms", ledger.pendingAfter).putBoolean("ambiguous", ledger.ambiguous).commit();
            storageFailed = !saved;
            return saved;
        } catch (RuntimeException failure) { storageFailed = true; return false; }
    }
}
