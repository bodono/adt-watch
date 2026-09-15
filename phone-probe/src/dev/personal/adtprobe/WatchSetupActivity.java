package dev.personal.adtprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.RequiresApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** User-approved association setup only. Does not connect a transport or invoke ADT. */
public final class WatchSetupActivity extends Activity {
    private static final int CHOOSE_DEVICE = 71;
    private static final int READ_PAIRED_DEVICES = 72;
    private CompanionDeviceManager manager;
    private LinearLayout column;
    private TextView outcome;
    private Button choose;
    private Button nearby;
    private Button refresh;
    private boolean busy;
    private boolean resumed;
    private boolean verified;
    private boolean permissionPending;
    private boolean pairedListReady;
    private boolean chooserOpen;
    private int requestNumber;
    private IntentSender pendingChooser;
    private AlertDialog pairedChooser;

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(10), 0, dp(10)); column.addView(view); return view;
    }
    private Button button(String label, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(label); view.setAllCaps(false); view.setMinHeight(dp(54));
        view.setOnClickListener(listener); column.addView(view); return view;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 22, 30));
        column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(18), dp(22), dp(24));
        scroll.addView(column); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout());
            scroll.setPadding(edges.left, edges.top, edges.right, edges.bottom); return insets;
        });
        text("Pixel Watch setup", 28);
        text("Choose your Pixel Watch from the phone's already paired devices, then confirm it in Android's "
            + "companion-device chooser. Check its name carefully; if you cannot identify your watch, cancel.", 17);
        text("This creates a companion association for ADT Phone Test. It does not connect the watch "
            + "to this app or send an alarm command. Alarm control while the phone is locked still needs a separate test.", 15);
        text("The paired-device list needs Bluetooth permission. Names are shown only here; this app does not "
            + "save or log device names or addresses. Keep Bluetooth on and the watch nearby. "
            + "The optional nearby search may also need the phone's Location setting on.", 15);
        outcome = text("Checking existing associations…", 17);
        choose = button("Choose already paired watch…", view -> {
            if (pendingChooser != null) openChooser();
            else if (pairedListReady) {
                pairedListReady = false;
                showPairedDevices(requestNumber);
            } else beginPairedChoice();
        });
        nearby = button("Search nearby devices instead…", view -> beginNearbyAssociation());
        refresh = button("Refresh association status", view -> refreshAssociations());
        button("Done", view -> finish());
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            choose.setEnabled(false);
            nearby.setEnabled(false);
            refresh.setEnabled(false);
            outcome.setText("This phone does not offer Android companion-device setup.");
            return;
        }
        manager = getSystemService(CompanionDeviceManager.class);
        if (manager == null) {
            choose.setEnabled(false);
            nearby.setEnabled(false);
            refresh.setEnabled(false);
            outcome.setText("Android companion-device setup is unavailable.");
            return;
        }
        refreshAssociations();
    }

    private int beginRequest() {
        busy = true; verified = false; pendingChooser = null;
        pairedListReady = false; chooserOpen = false;
        choose.setEnabled(false); nearby.setEnabled(false); refresh.setEnabled(false);
        return ++requestNumber;
    }

    private void beginPairedChoice() {
        if (manager == null || busy || !resumed) return;
        int request = beginRequest();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true;
            outcome.setText("Allow Bluetooth access to choose from devices already paired with this phone.");
            try {
                requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, READ_PAIRED_DEVICES);
            } catch (Exception e) {
                finishOutcome("Bluetooth permission could not be requested. You can try setup again.",
                    "Watch setup: Bluetooth permission request failed (" + e.getClass().getSimpleName() + ").");
            }
        } else showPairedDevices(request);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != READ_PAIRED_DEVICES || !permissionPending || !busy || isFinishing() || isDestroyed()) return;
        permissionPending = false;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            finishOutcome("Bluetooth access was not granted. No device was selected. You can try again or use the nearby search.",
                "Watch setup: paired-device permission declined or canceled.");
        } else if (resumed) {
            showPairedDevices(requestNumber);
        } else {
            // Do not open a chooser over another app if the permission result arrives while paused.
            pairedListReady = true;
            choose.setEnabled(true);
            outcome.setText("Bluetooth access granted. Tap Choose already paired watch to continue.");
        }
    }

    private void showPairedDevices(int request) {
        if (!busy || request != requestNumber || !resumed || isFinishing() || isDestroyed() || pairedChooser != null) return;
        choose.setEnabled(false);
        // Permission may have changed since the runtime result or a previous chooser.
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pairedPermissionUnavailable();
            return;
        }
        try {
            BluetoothManager bluetooth = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bluetooth == null ? null : bluetooth.getAdapter();
            if (adapter == null) {
                finishOutcome("This phone has no available Bluetooth adapter.", "Watch setup: Bluetooth adapter unavailable.");
                return;
            }
            if (!adapter.isEnabled()) {
                finishOutcome("Bluetooth is off. Turn it on in Android settings, then choose your paired watch again.",
                    "Watch setup: Bluetooth is off.");
                return;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                finishOutcome("Android reports no already paired Bluetooth devices. Check your watch in the phone's "
                    + "Bluetooth settings, or try the nearby search.", "Watch setup: paired-device list was empty.");
                return;
            }
            // Keep devices and labels only in this dialog's transient memory, never in app storage or logs.
            final List<BluetoothDevice> devices = new ArrayList<>(bonded);
            String[] labels = new String[devices.size()];
            for (int n = 0; n < devices.size(); n++) {
                String name = devices.get(n).getName();
                if (name != null) name = name.replace('\n', ' ').replace('\r', ' ').trim();
                if (name == null || name.isEmpty()
                        || name.matches("(?i).*(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}.*"))
                    name = "Unnamed paired device " + (n + 1);
                labels[n] = name;
            }
            outcome.setText("Select your own Pixel Watch. If its name is missing or ambiguous, cancel; nothing is selected automatically.");
            pairedChooser = new AlertDialog.Builder(this)
                .setTitle("Choose your already paired watch")
                .setItems(labels, (dialog, which) -> {
                    pairedChooser = null;
                    if (!busy || request != requestNumber || isFinishing() || isDestroyed()) return;
                    if (Build.VERSION.SDK_INT >= 31
                            && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        pairedPermissionUnavailable();
                        return;
                    }
                    try {
                        BluetoothDevice selected = devices.get(which);
                        AssociationRequest association = new AssociationRequest.Builder()
                            .addDeviceFilter(new BluetoothDeviceFilter.Builder().setAddress(selected.getAddress()).build())
                            .setSingleDevice(true)
                            .build();
                        startAssociation(association, request, true);
                    } catch (SecurityException e) {
                        pairedPermissionUnavailable();
                    } catch (Exception e) {
                        finishOutcome("The selected device could not be passed to Android's chooser. Check Bluetooth access and try again.",
                            "Watch setup: selected-device request failed (" + e.getClass().getSimpleName() + ").");
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> cancelPairedChoice(request))
                .setOnCancelListener(dialog -> cancelPairedChoice(request))
                .create();
            pairedChooser.setOnDismissListener(dialog -> pairedChooser = null);
            pairedChooser.show();
        } catch (SecurityException e) {
            // A revocation can race the explicit check above. Do not include device data.
            pairedPermissionUnavailable();
        } catch (Exception e) {
            finishOutcome("Already paired devices could not be read. Check Bluetooth permission and try again, or use the nearby search.",
                "Watch setup: paired-device list failed (" + e.getClass().getSimpleName() + ").");
        }
    }

    private void pairedPermissionUnavailable() {
        finishOutcome("Bluetooth access is unavailable. No association was requested. Choose your paired watch again "
            + "to grant access, or use the nearby search.",
            "Watch setup: Bluetooth access unavailable during paired-device selection.");
    }

    private void cancelPairedChoice(int request) {
        if (busy && request == requestNumber)
            finishOutcome("Paired-watch selection canceled. No association was requested.",
                "Watch setup: paired-device chooser canceled.");
    }

    private void beginNearbyAssociation() {
        if (manager == null || busy || !resumed) return;
        int request = beginRequest();
        try {
            // No name/address restriction, self-managed association, device profile or role.
            AssociationRequest association = new AssociationRequest.Builder()
                .addDeviceFilter(new BluetoothDeviceFilter.Builder().build())
                .setSingleDevice(false)
                .build();
            startAssociation(association, request, false);
        } catch (Exception e) {
            finishOutcome("Android could not start companion setup. Check Bluetooth and Location settings, then try again.",
                "Watch setup: request failed (" + e.getClass().getSimpleName() + ").");
        }
    }

    private void startAssociation(AssociationRequest association, int request, boolean paired) {
        if (!busy || request != requestNumber || isFinishing() || isDestroyed()) return;
        outcome.setText("Waiting for Android's confirmation. Confirm only your own Pixel Watch.");
        try {
            Probe.event(this, paired
                ? "Watch setup: user selected an already paired device for native companion setup."
                : "Watch setup: user requested Android's nearby companion-device chooser.");
            if (Build.VERSION.SDK_INT >= 33) {
                Api33.associate(this, association, request);
            } else {
                manager.associate(association, new CompanionDeviceManager.Callback() {
                    @Override public void onDeviceFound(IntentSender sender) { chooserReady(request, sender); }
                    @Override public void onFailure(CharSequence error) { failed(request); }
                }, new Handler(Looper.getMainLooper()));
            }
        } catch (Exception e) {
            finishOutcome("Android could not start companion setup. Check Bluetooth and Location settings, then try again.",
                "Watch setup: request failed (" + e.getClass().getSimpleName() + ").");
        }
    }

    private void chooserReady(int request, IntentSender sender) {
        if (isDestroyed() || isFinishing() || request != requestNumber || verified || !busy
                || chooserOpen || pendingChooser != null) return;
        pendingChooser = sender;
        if (resumed) openChooser();
        else {
            choose.setText("Open Android device chooser…"); choose.setEnabled(true);
            outcome.setText("Android's chooser is ready. Tap to select your own Pixel Watch.");
        }
    }

    private void openChooser() {
        if (!resumed || pendingChooser == null || chooserOpen || !busy) return;
        IntentSender sender = pendingChooser; pendingChooser = null;
        chooserOpen = true;
        choose.setEnabled(false);
        try {
            startIntentSenderForResult(sender, CHOOSE_DEVICE, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException e) {
            finishOutcome("Android's device chooser could not open. Try setup again.",
                "Watch setup: chooser could not open.");
        }
    }

    private void failed(int request) {
        if (isDestroyed() || isFinishing() || request != requestNumber || verified || !busy) return;
        // Framework error text can contain device identifiers; do not display or log it.
        finishOutcome("Android did not create the association. If you canceled, nothing more is needed. "
            + "Otherwise check that your watch is nearby, with Bluetooth and Location available, then try again.",
            "Watch setup: Android reported association failure or cancellation.");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CHOOSE_DEVICE || verified || !busy || !chooserOpen) return;
        chooserOpen = false;
        if (result == RESULT_OK) {
            verifyAssociation(-1);
        } else {
            chooserFailed(result);
        }
    }

    private void chooserFailed(int result) {
        if (result == RESULT_CANCELED || result == CompanionDeviceManager.RESULT_USER_REJECTED) {
            finishOutcome("Companion setup canceled. No alarm command was sent.",
                "Watch setup: chooser canceled or association declined.");
        } else if (result == CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT) {
            finishOutcome("Android's device search timed out. Keep your watch nearby and try again when ready.",
                "Watch setup: device discovery timed out.");
        } else {
            finishOutcome("Android did not complete companion setup. You can try again.",
                "Watch setup: chooser returned an unsuccessful result.");
        }
    }

    private void verifyAssociation(int expectedId) {
        if (isDestroyed() || verified || manager == null) return;
        try {
            int count;
            if (Build.VERSION.SDK_INT >= 33) {
                count = Api33.associationCount(manager, expectedId);
            } else {
                // Only inspect the count. Never log, display or store the returned addresses.
                count = manager.getAssociations().size();
            }
            if (count <= 0) {
                finishOutcome("Android returned from setup, but the companion association could not yet be verified. "
                    + "Use Refresh association status to check again.",
                    "Watch setup: completion received but association not verified.");
                return;
            }
            verified = true;
            if (Build.VERSION.SDK_INT >= 33 && Build.VERSION.SDK_INT < 35) {
                finishOutcome("Android returned successfully from setup and reports " + count
                    + " association record(s) for this app. This Android version cannot verify their type here. "
                    + "Watch communication and alarm control are not established.",
                    "Watch setup: association record count=" + count + "; association type verification unavailable.");
                return;
            }
            finishOutcome("Companion association created/verified. Android reports " + count
                + " association(s) for this app. Watch communication and locked-phone alarm control are still untested.",
                "Watch setup: companion association verified; association count=" + count + ".");
        } catch (Exception e) {
            finishOutcome("The companion association could not be verified. Use Refresh association status to try again.",
                "Watch setup: verification failed (" + e.getClass().getSimpleName() + ").");
        }
    }

    private void refreshAssociations() {
        if (manager == null) return;
        try {
            int count = Build.VERSION.SDK_INT >= 33
                ? Api33.associationCount(manager, -1) : manager.getAssociations().size();
            if (Build.VERSION.SDK_INT >= 33 && Build.VERSION.SDK_INT < 35) {
                outcome.setText(count == 0 ? "Android reports no companion association records for this app."
                    : "Android reports " + count + " association record(s) for this app. Their type cannot be "
                        + "verified on this Android version. This does not establish watch communication or alarm control.");
                Probe.event(this, "Watch setup: association record count=" + count + "; association type verification unavailable.");
                return;
            }
            outcome.setText(count == 0 ? "No verified native companion association exists for this app yet."
                : "Companion association verified. Android reports " + count
                    + " association(s) for this app. This does not establish watch communication or alarm control.");
            Probe.event(this, "Watch setup: verified association count=" + count + ".");
        } catch (Exception e) {
            outcome.setText("Existing companion associations could not be checked.");
            Probe.event(this, "Watch setup: status check failed (" + e.getClass().getSimpleName() + ").");
        }
    }

    private void finishOutcome(String message, String event) {
        busy = false; pendingChooser = null; permissionPending = false;
        pairedListReady = false; chooserOpen = false;
        requestNumber++; // Late callbacks from the completed/canceled request cannot reopen a chooser.
        if (pairedChooser != null) { pairedChooser.dismiss(); pairedChooser = null; }
        choose.setText("Choose already paired watch…"); choose.setEnabled(manager != null);
        nearby.setEnabled(manager != null); refresh.setEnabled(manager != null);
        outcome.setText(message);
        Probe.event(this, event);
    }

    @Override public void onStart() {
        super.onStart(); Probe.activityVisible = true;
    }
    @Override public void onResume() {
        super.onResume(); resumed = true;
        if (pairedListReady && busy) {
            pairedListReady = false;
            int request = requestNumber;
            getWindow().getDecorView().post(() -> showPairedDevices(request));
        }
    }
    @Override public void onPause() { resumed = false; super.onPause(); }
    @Override public void onStop() { Probe.activityVisible = false; super.onStop(); }
    @Override public void onDestroy() {
        requestNumber++; pendingChooser = null; pairedListReady = false;
        permissionPending = false; busy = false;
        if (pairedChooser != null) { pairedChooser.dismiss(); pairedChooser = null; }
        super.onDestroy();
    }

    /** Keep API 33 association classes and overloads out of the older execution path. */
    @RequiresApi(33)
    private static final class Api33 {
        static void associate(WatchSetupActivity activity, AssociationRequest request, int number) {
            activity.manager.associate(request, activity.getMainExecutor(), new CompanionDeviceManager.Callback() {
                @Override public void onAssociationPending(IntentSender sender) { activity.chooserReady(number, sender); }
                @Override public void onAssociationCreated(AssociationInfo association) {
                    if (number == activity.requestNumber && !activity.isFinishing())
                        activity.verifyAssociation(association.getId());
                }
                @Override public void onFailure(CharSequence error) { activity.failed(number); }
                @Override public void onFailure(int code, CharSequence error) {
                    if (number == activity.requestNumber && !activity.isDestroyed() && !activity.verified)
                        activity.chooserFailed(code);
                }
            });
        }

        static int associationCount(CompanionDeviceManager manager, int expectedId) {
            List<AssociationInfo> associations = manager.getMyAssociations();
            int count = 0;
            boolean found = expectedId < 0;
            for (AssociationInfo association : associations) {
                // Public type inspection is available only from API 35. API 33/34 callers
                // explicitly report records with unknown type instead of fabricating a result.
                if (Build.VERSION.SDK_INT >= 35 && association.isSelfManaged()) continue;
                count++;
                if (association.getId() == expectedId) found = true;
            }
            return found ? count : 0;
        }
    }
}
