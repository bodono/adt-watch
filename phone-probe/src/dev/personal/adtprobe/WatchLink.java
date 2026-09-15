package dev.personal.adtprobe;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;

/** Stores only the last harmless ping's local receipt time and acknowledgement outcome. */
public final class WatchLink {
    private static final String PREFERENCES = "watch_link_status";
    private static long receiptSequence;

    private WatchLink() { }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static synchronized long received(Context context) {
        long ticket = ++receiptSequence;
        preferences(context).edit().putLong("received_at", System.currentTimeMillis())
            .putInt("ack_status", 0).apply();
        return ticket;
    }

    static synchronized void acknowledged(Context context, long ticket, boolean acceptedByTransport) {
        if (ticket != receiptSequence) return;
        preferences(context).edit().putInt("ack_status", acceptedByTransport ? 1 : 2).apply();
    }

    public static synchronized String status(Context context) {
        SharedPreferences stored = preferences(context);
        long receivedAt = stored.getLong("received_at", 0);
        if (receivedAt <= 0) return "No watch connection test received yet. Alarm state not checked.";
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(new Date(receivedAt));
        String outcome;
        switch (stored.getInt("ack_status", 0)) {
            case 1: outcome = "Reply submitted; delivery is unverified."; break;
            case 2: outcome = "Reply delivery could not be confirmed."; break;
            default: outcome = "Reply outcome is unavailable."; break;
        }
        return "Watch test received " + when + ". " + outcome + " Alarm state not checked.";
    }
}
