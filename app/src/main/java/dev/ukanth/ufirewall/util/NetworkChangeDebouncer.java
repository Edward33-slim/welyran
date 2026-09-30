/**
 * Debounces rapid network connectivity changes to avoid excessive iptables rule applications.
 * <p>
 * Copyright (C) 2025 Umakanthan Chandran
 */
package dev.ukanth.ufirewall.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.ukanth.ufirewall.Api;
import dev.ukanth.ufirewall.InterfaceTracker;
import dev.ukanth.ufirewall.log.Log;

public class NetworkChangeDebouncer {
    private static final String TAG = "AFWall";
    private static final long DEFAULT_DEBOUNCE_DELAY_MS = 2000;
    // After the firewall is fully disabled, wait exactly 5 seconds before enabling it.
    private static final long WIFI_RECONNECT_REENABLE_DELAY_MS = 5000;
    private static final long RETRY_DELAY_MS = 500;
    private static final int MAX_RETRY_ATTEMPTS = 10;

    private static final String WIFI_RECONNECT_PREFS = "wifi_reconnect_state";
    private static final String PREF_WIFI_DISCONNECTED = "wifi_disconnected";
    private static final String PREF_FIREWALL_WAS_ENABLED = "firewall_was_enabled";

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final AtomicReference<Runnable> pendingRunnable = new AtomicReference<>(null);
    private static final AtomicReference<String> latestReason = new AtomicReference<>(null);
    private static final AtomicReference<Context> latestContext = new AtomicReference<>(null);
    private static final AtomicBoolean isScheduled = new AtomicBoolean(false);
    private static volatile int retryCount = 0;
    private static volatile long lastChangeTimestamp = 0;
    private static volatile int coalescedCount = 0;

    /** Remember that Wi-Fi disconnected while the firewall was enabled. */
    public static void markWifiDisconnected(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        android.content.SharedPreferences prefs = app.getSharedPreferences(
                WIFI_RECONNECT_PREFS, Context.MODE_PRIVATE);
        boolean firewallEnabled = Api.isEnabled(app);
        prefs.edit()
                .putBoolean(PREF_WIFI_DISCONNECTED, true)
                .putBoolean(PREF_FIREWALL_WAS_ENABLED, firewallEnabled)
                .apply();
        Log.i(TAG, "Wi-Fi disconnected; firewall was enabled: " + firewallEnabled);
    }

    /**
     * Called when Wi-Fi reconnects. The firewall reset starts immediately here:
     * disable first, wait for the disable callback, wait 5 seconds, then enable.
     */
    public static boolean handleWifiConnected(Context context) {
        if (context == null) return false;
        final Context app = context.getApplicationContext();
        android.content.SharedPreferences prefs = app.getSharedPreferences(
                WIFI_RECONNECT_PREFS, Context.MODE_PRIVATE);
        boolean wasDisconnected = prefs.getBoolean(PREF_WIFI_DISCONNECTED, false);
        boolean firewallWasEnabled = prefs.getBoolean(PREF_FIREWALL_WAS_ENABLED, false);

        if (!wasDisconnected || !firewallWasEnabled || !Api.isEnabled(app)) {
            if (!Api.isEnabled(app)) prefs.edit().clear().apply();
            return false;
        }

        // Consume the marker before starting so duplicate broadcasts cannot start two cycles.
        prefs.edit().clear().apply();
        cancelPendingJob();

        // IMPORTANT: no debounce delay here. Start disabling immediately on reconnect.
        Log.i(TAG, "Wi-Fi reconnected; starting firewall disable immediately");
        runWifiReconnectCycle(app);
        return true;
    }

    /** Disable first; only after successful completion wait 5 seconds, then enable. */
    private static void runWifiReconnectCycle(final Context context) {
        if (!Api.isEnabled(context)) {
            Log.d(TAG, "Wi-Fi reconnect firewall reset skipped: firewall is already disabled");
            return;
        }

        Log.i(TAG, "Wi-Fi reconnect: disabling firewall now");
        FirewallActions.setEnabled(context, false, false, new FirewallActions.Done() {
            @Override
            public void done(boolean disabledSuccessfully) {
                if (!disabledSuccessfully) {
                    Log.e(TAG, "Wi-Fi reconnect: firewall disable failed; not enabling it again");
                    return;
                }

                Log.i(TAG, "Wi-Fi reconnect: firewall disabled; waiting 5 seconds");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Log.i(TAG, "Wi-Fi reconnect: 5 seconds completed; enabling firewall");
                        FirewallActions.setEnabled(context, true, false, new FirewallActions.Done() {
                            @Override
                            public void done(boolean enabledSuccessfully) {
                                if (enabledSuccessfully) {
                                    Log.i(TAG, "Wi-Fi reconnect: firewall re-enabled successfully");
                                } else {
                                    Log.e(TAG, "Wi-Fi reconnect: firewall re-enable failed");
                                }
                            }
                        });
                    }
                }, WIFI_RECONNECT_REENABLE_DELAY_MS);
            }
        });
    }

    public static void scheduleNetworkChange(Context context, String reason) {
        cancelPendingJob();
        latestContext.set(context.getApplicationContext());
        latestReason.set(reason);
        lastChangeTimestamp = System.currentTimeMillis();
        long debounceDelay = getDebounceDelay();

        if (isScheduled.get()) {
            coalescedCount++;
            Log.d(TAG, "Network change coalesced (total: " + coalescedCount + "): " + reason);
        } else {
            coalescedCount = 0;
            retryCount = 0;
            Log.d(TAG, "Network change scheduled with " + debounceDelay + "ms delay: " + reason);
        }

        Runnable applyRulesRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = latestContext.get();
                    String finalReason = latestReason.get();
                    if (ctx != null && finalReason != null) {
                        if (Api.isRulesBeingApplied()) {
                            if (retryCount < MAX_RETRY_ATTEMPTS) {
                                retryCount++;
                                handler.postDelayed(this, RETRY_DELAY_MS);
                                return;
                            }
                        }
                        InterfaceTracker.applyRulesOnChange(ctx, finalReason);
                        coalescedCount = 0;
                        retryCount = 0;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error applying rules after debounce: " + e.getMessage(), e);
                } finally {
                    if (retryCount == 0 || retryCount >= MAX_RETRY_ATTEMPTS) {
                        isScheduled.set(false);
                        pendingRunnable.set(null);
                    }
                }
            }
        };

        pendingRunnable.set(applyRulesRunnable);
        isScheduled.set(true);
        handler.postDelayed(applyRulesRunnable, debounceDelay);
    }

    private static void cancelPendingJob() {
        Runnable existingRunnable = pendingRunnable.getAndSet(null);
        if (existingRunnable != null) handler.removeCallbacks(existingRunnable);
        isScheduled.set(false);
    }

    public static boolean isPending() {
        return isScheduled.get();
    }

    private static long getDebounceDelay() {
        try {
            int delaySeconds = G.getNetworkDebounceDelay();
            if (delaySeconds > 0 && delaySeconds <= 30) return delaySeconds * 1000L;
        } catch (Exception e) {
            Log.w(TAG, "Error reading debounce delay preference: " + e.getMessage());
        }
        return DEFAULT_DEBOUNCE_DELAY_MS;
    }

    public static void flushPending() {
        Runnable existingRunnable = pendingRunnable.getAndSet(null);
        if (existingRunnable != null) {
            handler.removeCallbacks(existingRunnable);
            existingRunnable.run();
        }
    }

    public static void clear() {
        cancelPendingJob();
        latestContext.set(null);
        latestReason.set(null);
        coalescedCount = 0;
        retryCount = 0;
    }
}