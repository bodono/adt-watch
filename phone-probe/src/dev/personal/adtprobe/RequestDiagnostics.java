package dev.personal.adtprobe;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/** Small, private, rotating diagnostics. Recording never sends a message, reads ADT or schedules work. */
final class RequestDiagnostics {
    static final String FILE_NAME = "request-diagnostics.log";
    static final int MAX_FILE_BYTES = 128 * 1024;
    enum QueryOutcome {
        READY, REUSED, NO_BINDING, LOCK_WAIT, BINDING_CHANGED, TIMEOUT, INTERRUPTED,
        EXCEPTION, NO_RESULT, FAILED, UNSUPPORTED_OBSERVATION, STORAGE
    }
    enum RecoveryStage { START, RESULT }
    private static String lastPassiveFailure;
    private static long lastPassiveElapsed = -1;
    private static int skippedPassive;
    private RequestDiagnostics() { }

    /** A bounded, read-only view for Advanced setup; opening it never requests live status. */
    static synchronized String recent(Context context) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (String name : new String[] {FILE_NAME + ".1", FILE_NAME}) {
            try (FileInputStream input = context.openFileInput(name)) {
                int count, remaining = MAX_FILE_BYTES;
                while (remaining > 0 && (count = input.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                    bytes.write(buffer, 0, count); remaining -= count;
                }
            } catch (Exception ignored) { }
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        if (text.isEmpty()) return "No watch request diagnostics recorded yet.";
        int start = text.length() > 24_000 ? text.indexOf('\n', text.length() - 24_000) + 1 : 0;
        return text.substring(start);
    }

    static void tap(Context context, AlarmAction action, String request) {
        write(context, "stage=TAP" + request(action, request));
    }

    static void declined(Context context, AlarmAction action, String request, AlarmStateProtocol.DeclineReason reason) {
        write(context, "stage=DECLINED" + request(action, request) + " reason=" + (reason == null ? "UNAVAILABLE" : reason.name()));
    }

    static void requested(Context context, AlarmAction action, String request) {
        write(context, "stage=CLICK_ATTEMPTED" + request(action, request));
    }

    /** Successful ambient reads are deliberately absent: overnight polling must not evict tap failures. */
    static void query(Context context, AlarmStateProtocol.QueryIntent intent, AlarmAction action, String request,
            long duration, QueryOutcome outcome, AdtPortalClient.Result result, AlarmStateProtocol.DeclineReason failure) {
        if (intent != AlarmStateProtocol.QueryIntent.USER && failure == null) return;
        String line = "stage=QUERY_RESULT" + request(action, request)
            + " intent=" + (intent == AlarmStateProtocol.QueryIntent.USER ? "USER" : "PASSIVE")
            + " durationMs=" + Math.max(0, duration) + " outcome=" + outcome.name()
            + " reason=" + (failure == null ? "NONE" : failure.name());
        if (result != null) line += " status=" + result.status.name() + " code=" + result.diagnosticCode()
            + " state=" + result.state.name() + " providerBusy=" + Boolean.TRUE.equals(result.loading);
        if (intent != AlarmStateProtocol.QueryIntent.USER) {
            String key = outcome.name() + "/" + failure.name()
                + (result == null ? "" : "/" + result.status.name() + "/" + result.diagnosticCode());
            synchronized (RequestDiagnostics.class) {
                long now = SystemClock.elapsedRealtime();
                if (key.equals(lastPassiveFailure) && now >= lastPassiveElapsed && now - lastPassiveElapsed < 60_000) {
                    skippedPassive = Math.min(Integer.MAX_VALUE - 1, skippedPassive) + 1;
                    return;
                }
                line += " suppressedPassive=" + skippedPassive;
                skippedPassive = 0; lastPassiveFailure = key; lastPassiveElapsed = now;
            }
        }
        write(context, line);
    }

    static void recovery(Context context, RecoveryStage stage, boolean manual, String code, long duration) {
        write(context, "stage=RECOVERY_" + stage.name() + " manual=" + manual
            + " code=" + recoveryCode(code) + " durationMs=" + Math.max(0, duration));
    }

    private static String request(AlarmAction action, String request) {
        return " action=" + (action == null ? "NONE" : action.name()) + " request=" + token(request);
    }

    /** UUIDs contain no account data, but even these are never stored verbatim. */
    static String token(String request) {
        if (!AlarmStateProtocol.uuid(request)) return "-";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.US_ASCII));
            StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                out.append(Character.forDigit((digest[i] >>> 4) & 15, 16));
                out.append(Character.forDigit(digest[i] & 15, 16));
            }
            return out.toString();
        } catch (Exception ignored) { return "-"; }
    }

    /** Accept only the vocabulary generated by the login/status clients, never arbitrary persisted text. */
    private static String recoveryCode(String code) {
        if (code == null) return "UNAVAILABLE";
        switch (code) {
            case "STARTED": case "READY": case "DISABLED": case "WEBSITE_OPEN": case "BUSY":
            case "CANCELLED": case "CREDENTIALS": case "COOLDOWN": case "TIMEOUT": case "STORAGE":
            case "UNAVAILABLE": case "LOGIN/UNAVAILABLE": case "VERIFY/UNAVAILABLE": return code;
            default: break;
        }
        String value = code.startsWith("VERIFY/") ? code.substring(7) : code;
        String[] fields = value.split("/", -1);
        if (fields.length != 2 && fields.length != 3) return "UNAVAILABLE";
        if (fields.length == 3 && !fields[2].matches("[1-5][0-9]{2}")) return "UNAVAILABLE";
        try {
            if (code.startsWith("VERIFY/")) {
                AdtPortalClient.Stage.valueOf(fields[0]); AdtPortalClient.Reason.valueOf(fields[1]);
            } else {
                AdtLoginClient.Stage.valueOf(fields[0]); AdtLoginClient.Reason.valueOf(fields[1]);
            }
            return code;
        } catch (IllegalArgumentException ignored) { return "UNAVAILABLE"; }
    }

    private static synchronized void write(Context context, String closedFields) {
        try {
            byte[] bytes = (Instant.now() + " elapsedMs=" + SystemClock.elapsedRealtime() + " " + closedFields + "\n")
                .getBytes(StandardCharsets.UTF_8);
            File current = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
            if (current.length() + bytes.length > MAX_FILE_BYTES) {
                File previous = new File(current.getParentFile(), FILE_NAME + ".1");
                if (previous.exists() && !previous.delete()) return;
                if (!current.renameTo(previous)) return;
            }
            try (FileOutputStream output = new FileOutputStream(current, true)) { output.write(bytes); }
        } catch (Exception ignored) { /* Diagnostics must never affect an alarm request or status read. */ }
    }
}
