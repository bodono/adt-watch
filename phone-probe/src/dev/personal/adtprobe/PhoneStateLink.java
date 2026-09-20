package dev.personal.adtprobe;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Wearable;
import java.util.concurrent.TimeUnit;

/** Shares only normalized reported alarm state with the approved companion. */
final class PhoneStateLink {
    private PhoneStateLink() { }
    static void reply(Context context, String source, AlarmStateProtocol.Query query) {
        if (query == null) return;
        String request = query.request;
        if (!WatchProtocol.validNodeId(source) || !AlarmStateProtocol.uuid(request)) return;
        RoutineAccess.Snapshot access = RoutineAccess.snapshot(context);
        AlarmStateProtocol.Report report;
        if (access == null) {
            report = new AlarmStateProtocol.Report(request, AlarmStateProtocol.State.UNKNOWN,
                AlarmStateProtocol.Availability.SETUP, "-", 0);
        } else {
            if (!source.equals(access.nodeId)) return;
            PhoneAlarmState.refresh(context, PhoneAlarmState.REUSE_MS, query.intent);
            if (!RoutineAccess.stillValid(context, access)) return;
            report = report(context, request);
        }
        Log.i("AdtPhoneStatus", SystemClock.elapsedRealtime() + " REPLY availability=" + report.availability.name());
        try {
            // Called on WearableListenerService's worker, not the UI thread.
            Tasks.await(Wearable.getMessageClient(context).sendMessage(source,
                AlarmStateProtocol.STATE_PATH, report.encode()), 2, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { /* No retry and no command. */ }
    }
    static void publish(Context context) {
        RoutineAccess.Snapshot access = RoutineAccess.snapshot(context);
        if (access == null) return;
        try {
            AlarmStateProtocol.Report report = report(context, "-");
            Log.i("AdtPhoneStatus", SystemClock.elapsedRealtime() + " PUBLISH availability=" + report.availability.name());
            Wearable.getMessageClient(context).sendMessage(access.nodeId,
                AlarmStateProtocol.STATE_PATH, report.encode());
        }
        catch (RuntimeException ignored) { /* A later explicit query can recover state. */ }
    }
    private static AlarmStateProtocol.Report report(Context context, String request) {
        PhoneAlarmState.Snapshot s = PhoneAlarmState.snapshot(context);
        return new AlarmStateProtocol.Report(request, s.state, s.availability, s.revision, s.ageMillis, s.completedRequest, s.evidence, s.observationId);
    }
}
