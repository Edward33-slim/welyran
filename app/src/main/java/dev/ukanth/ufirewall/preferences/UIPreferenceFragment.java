package dev.ukanth.ufirewall.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;

import dev.ukanth.ufirewall.Api;
import dev.ukanth.ufirewall.R;
import dev.ukanth.ufirewall.util.G;

public class UIPreferenceFragment extends PreferenceFragment  implements
		SharedPreferences.OnSharedPreferenceChangeListener {

	private Context ctx;
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// Load the preferences from an XML resource
		addPreferencesFromResource(R.xml.ui_preferences);
		Preference notificationSettings = findPreference("notification_settings");
		if (notificationSettings != null) {
			// importance, sound, lock screen... of each notification are Android settings once the
			// channels exist; an app setting can't change them
			notificationSettings.setOnPreferenceClickListener(preference -> {
				openNotificationSettings(getActivity());
				return true;
			});
		}
	}

	public static void openNotificationSettings(android.app.Activity activity) {
		if (activity == null) {
			return;
		}
		android.content.Intent intent;
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
			intent = new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
					.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
		} else {
			intent = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
					android.net.Uri.fromParts("package", activity.getPackageName(), null));
		}
		try {
			activity.startActivity(intent);
		} catch (Exception e) {
			Api.toast(activity, e.getMessage());
		}
	}

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		ctx = context;
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
		if(ctx == null) {
			ctx = getActivity();
		}
	}


}
