package dev.personal.adtprobe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Local setup logging and plain ADT navigation. No saved alarm command support. */
final class Probe {
    static volatile boolean activityVisible;

    static Intent neutralIntent() {
        return new Intent(Intent.ACTION_MAIN)
            .setComponent(new ComponentName("com.adtuk.adtukalarm",
                "com.alarm.alarmmobile.android.feature.auth.login.LoginActivity"))
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    static synchronized void event(Context context, String message) {
        String line = Instant.now().toString() + "  " + message + "\n";
        Log.i("AdtPhoneProbe", message); // Callers use generic outcomes, never private identifiers.
        try (FileOutputStream out = context.openFileOutput("events.txt", Context.MODE_APPEND)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    static String events(Context context) {
        try (InputStream in = context.openFileInput("events.txt")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            String all = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            return all.length() > 8000 ? all.substring(all.length() - 8000) : all;
        } catch (Exception ignored) { return "No test attempts yet."; }
    }
}
