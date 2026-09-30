package dev.ukanth.ufirewall.preferences;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.ukanth.ufirewall.Api;
import dev.ukanth.ufirewall.R;
import dev.ukanth.ufirewall.service.RootCommand;
import dev.ukanth.ufirewall.util.G;

public class RulesPreferenceFragment extends PreferenceFragment implements
        SharedPreferences.OnSharedPreferenceChangeListener {

    private Context ctx;
    private final BootAdvancedPreferences bootAdvanced = new BootAdvancedPreferences(this);


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.rules_preferences);
        bootAdvanced.bind();

        try {
            updateRuleStatus();
        } catch (Exception e) {
        }

        //make sure Roaming is disable in Wifi-only Tablets
        if (!Api.isMobileNetworkSupported(getActivity())) {
            CheckBoxPreference roamPreference = (CheckBoxPreference) findPreference("enableRoam");
            roamPreference.setChecked(false);
            roamPreference.setEnabled(false);
        } else {
            CheckBoxPreference roamPreference = (CheckBoxPreference) findPreference("enableRoam");
            roamPreference.setEnabled(true);
        }
    }

    private void updateRuleStatus() {
        Api.getChainStatus(ctx, new RootCommand()
                .setFailureToast(R.string.error_apply)
                .setLogging(true)
                .setCallback(new RootCommand.Callback() {
                    @Override
                    public void cbFunc(RootCommand state) {
                        if (state.exitCode == 0 && state.res != null) {
                            String output = state.res.toString();

                            Matcher matcher = Pattern.compile("-P INPUT (\\w+)").matcher(output);
                            if (matcher.find()) {
                                G.ipv4Input(matcher.group(1).equals("ACCEPT"));
                            }

                            Matcher matcher2 = Pattern.compile("-P OUTPUT (\\w+)").matcher(output);
                            if (matcher2.find()) {
                                G.ipv4Output(matcher2.group(1).equals("ACCEPT"));
                            }

                            Matcher matcher3 = Pattern.compile("-P FORWARD (\\w+)").matcher(output);
                            if (matcher3.find()) {
                                G.ipv4Fwd(matcher3.group(1).equals("ACCEPT"));
                            }

                            android.app.Activity activity = getActivity();
                            if (activity != null) {
                                activity.runOnUiThread(() -> {
                                    if (isAdded()) {
                                        getPreferenceScreen().removeAll();
                                        addPreferencesFromResource(R.xml.rules_preferences);
                                        bootAdvanced.bind();
                                    }
                                });
                            }
                        }
                    }
                }));
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        ctx = context;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M){
            ctx = activity;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);

    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {

        if (key.equals("activeRules")) {
            if (!G.activeRules()) {
                //disable service when there is no active rules
                //stopService(new Intent(PreferencesActivity.this, RootShell.class));

            }
        }

        //do chain apply for ipv4
        switch (key) {
            case "input_chain": {
                String rule = "-P INPUT " + (G.ipv4Input() ? "ACCEPT" : "DROP");
                Api.applyRule(ctx, rule, false, new RootCommand()
                        .setFailureToast(R.string.error_apply)
                        .setCallback(new RootCommand.Callback() {
                            @Override
                            public void cbFunc(RootCommand state) {
                                if (state.exitCode == 0) {
                                } else {
                                }
                            }
                        }));
                break;
            }
            case "output_chain": {
                String rule = "-P OUTPUT " + (G.ipv4Output() ? "ACCEPT" : "DROP");
                Api.applyRule(ctx, rule, false, new RootCommand()
                        .setFailureToast(R.string.error_apply)
                        .setCallback(new RootCommand.Callback() {
                            @Override
                            public void cbFunc(RootCommand state) {
                                if (state.exitCode == 0) {
                                } else {
                                }
                            }
                        }));
                break;
            }
            case "forward_chain": {
                String rule = "-P FORWARD " + (G.ipv4Fwd() ? "ACCEPT" : "DROP");
                Api.applyRule(ctx, rule, false, new RootCommand()
                        .setFailureToast(R.string.error_apply)
                        .setCallback(new RootCommand.Callback() {
                            @Override
                            public void cbFunc(RootCommand state) {
                                if (state.exitCode == 0) {
                                } else {
                                }
                            }
                        }));
                break;
            }
            case "input_chain_v6": {
                String rule = "-P INPUT " + (G.ipv6Input() ? "ACCEPT" : "DROP");
                Api.applyRule(ctx, rule, true, new RootCommand()
                        .setFailureToast(R.string.error_apply)
                        .setCallback(new RootCommand.Callback() {
                            @Override
                            public void cbFunc(RootCommand state) {
                                if (state.exitCode == 0) {
                                } else {
                                }
                            }
                        }));
                break;
            }
            case "output_chain_v6": {
                String rule = "-P OUTPUT " + (G.ipv6Output() ? "ACCEPT" : "DROP");
                Api.applyRule(ctx, rule, true, new RootCommand()
                        .setFailureToast(R.string.error_apply)
                        .setCallback(new RootCommand.Callback() {
                            @Override
                            public void cbFunc(RootCommand state) {
                                if (state.exitCode == 0) {
                                } else {
                                }
                            }
                        }));
                break;
            }
            case "forward_chain_v6": {
                String rule = "-P FORWARD " + (G.ipv6Fwd() ? "ACCEPT" : "DROP");
                Api.applyRule(ctx, rule, true, new RootCommand()
                        .setFailureToast(R.string.error_apply)
                        .setCallback(new RootCommand.Callback() {
                            @Override
                            public void cbFunc(RootCommand state) {
                                if (state.exitCode == 0) {
                                } else {
                                }
                            }
                        }));
                break;
            }
        }

        if (key.equals("enableIPv6"))

        {
            File defaultIP6TablesPath = new File("/system/bin/ip6tables");
            if (!defaultIP6TablesPath.exists()) {
                G.enableIPv6(false);
                CheckBoxPreference enable = (CheckBoxPreference) findPreference("enableIPv6");
                enable.setChecked(false);

                /*CheckBoxPreference block = (CheckBoxPreference) findPreference("blockIPv6");
                block.setChecked(false);
                if (ctx != null) {
                    Api.toast(ctx, getString(R.string.ip6unavailable));
                }*/
            } else {
                switch (key) {
                    case "enableIPv6":
                        CheckBoxPreference block = (CheckBoxPreference) findPreference("controlIPv6");
                        block.setChecked(false);
                        break;
                    case "controlIPv6":
                        CheckBoxPreference allow = (CheckBoxPreference) findPreference("enableIPv6");
                        allow.setChecked(false);
                        break;
                }
            }
        }

        if (key.equals("controlIPv6")) {
            CheckBoxPreference allow = (CheckBoxPreference) findPreference("enableIPv6");
            allow.setChecked(false);
        }

    }
}
