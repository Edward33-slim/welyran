/**
 * Detect the connectivity changes (for roaming and LAN subnet changes)
 * <p>
 * Copyright (C) 2011-2012  Umakanthan Chandran
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * @author Umakanthan Chandran
 * @version 1.0
 */
package dev.ukanth.ufirewall.broadcast;

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import dev.ukanth.ufirewall.Api;
import dev.ukanth.ufirewall.InterfaceTracker;
import dev.ukanth.ufirewall.log.Log;
import dev.ukanth.ufirewall.util.BootRuleManager;
import dev.ukanth.ufirewall.util.G;
import dev.ukanth.ufirewall.util.NetworkChangeDebouncer;

public class ConnectivityChangeReceiver extends BroadcastReceiver {

    public static final String TAG = "AFWall";

    // These are marked "@hide" in WifiManager.java
    public static final String WIFI_AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    public static final String TETHER_STATE_CHANGED_ACTION = "android.net.conn.TETHER_STATE_CHANGED";
    public static final String EXTRA_WIFI_AP_STATE = "wifi_state";
    public static final String EXTRA_PREVIOUS_WIFI_AP_STATE = "previous_wifi_state";


    @Override
    public void onReceive(final Context context, Intent intent) {
        Api.noteNetworkChange();

        final String action = intent.getAction();

        // A Wi-Fi disconnect followed by a Wi-Fi reconnect needs a complete firewall
        // reset on some devices. Record the disconnect even when another network (for
        // example mobile data) remains available, then handle the reconnect specially.
        if (CONNECTIVITY_ACTION.equals(action)) {
            NetworkInfo networkInfo = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            if (networkInfo != null && networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
                if (networkInfo.isConnected()) {
                    if (NetworkChangeDebouncer.handleWifiConnected(context)) {
                        Log.i(TAG, "Wi-Fi reconnected after disconnect; scheduled firewall disable/enable cycle.");
                        return;
                    }
                } else {
                    NetworkChangeDebouncer.markWifiDisconnected(context);
                }
            }
        }

        int status = Api.getConnectivityStatus(context);
        if (status > 0) {

            // NOTE: this gets called for wifi/3G/tether/roam changes but not VPN connect/disconnect
            // This will prevent applying rules when the user disable the option in preferences. This is for low end devices
            if (WIFI_AP_STATE_CHANGED_ACTION.equals(action)) {
                int newState = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1);
                int oldState = intent.getIntExtra(EXTRA_PREVIOUS_WIFI_AP_STATE, -1);
                Log.d(TAG, "OS reported AP state change: " + oldState + " -> " + newState);
            // Note: AP state changes are logged but don't trigger rule application during boot
            }

            if (Api.isEnabled(context) && G.activeRules()) {
                String reason = CONNECTIVITY_ACTION.equals(action) ?
                    InterfaceTracker.CONNECTIVITY_CHANGE : InterfaceTracker.TETHER_STATE_CHANGED;

                // Check with BootRuleManager if we should process this network change
                if (!BootRuleManager.shouldProcessNetworkChange(context, reason)) {
                    Log.d(TAG, "Network change ignored during boot process: " + reason);
                    return;
                }

                if (CONNECTIVITY_ACTION.equals(action)) {
                    Log.i(TAG, "Network change captured.");
                    NetworkChangeDebouncer.scheduleNetworkChange(context, InterfaceTracker.CONNECTIVITY_CHANGE);
                } else if (TETHER_STATE_CHANGED_ACTION.equals(action)) {
                    Log.i(TAG, "Tether change captured.");
                    NetworkChangeDebouncer.scheduleNetworkChange(context, InterfaceTracker.TETHER_STATE_CHANGED);
                }
            }
        }
    }
}