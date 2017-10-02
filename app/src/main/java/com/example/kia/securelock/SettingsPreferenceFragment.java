package com.example.kia.securelock;



import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.util.Log;
import android.widget.Toast;
import java.util.regex.Pattern;



/***
 * Created by Chiara Caiazza on 27/08/2017.
 */
public class SettingsPreferenceFragment extends PreferenceFragment {
    private static final String EMAIL_PATTERN= "^[_A-Za-z0-9]+(\\.[_A-Za-z0-9-]+)*@[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";
    static final String TAG = "SettingsPreferenceFrag";
    private final String GPS_ENABLE = "pref_key_alarm_GPS";
    private final String PHOTO_ENABLE = "pref_key_alarm_photo";
    private SharedPreferences sharedPreferences;



    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());

        if (AlarmService.proximitySensor == null)
            //proximity sensor not available
            findPreference("pref_key_alarm_trigger_proximity_switch").setEnabled(false);

        if (AlarmService.accelerometerSensor == null)
            //accelerometer sensor not available
            findPreference("pref_key_alarm_trigger_accelerometer_switch").setEnabled(false);

        final SwitchPreference GPSPreference = (SwitchPreference)findPreference(GPS_ENABLE);
        final SwitchPreference photoPreference = (SwitchPreference)findPreference(PHOTO_ENABLE);

        if (!sharedPreferences.getBoolean(GPS_ENABLE, true))
            photoPreference.setEnabled(false);

        //check the email address inserted
        final EditTextPreference emailAddress = (EditTextPreference)findPreference("pref_key_email_receiver");
        emailAddress.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                Log.i(TAG, "onPreferenceChange(): email address changed!");

                String address = emailAddress.getText().trim();

                //if no text return
                if (newValue.equals("")) {
                    emailAddress.setText("");

                    Toast.makeText(getActivity().getApplicationContext(), "Email address discarded",
                              Toast.LENGTH_LONG).show();
                    return false;
                }

                // pattern doesn't match so returning false
                if (!Pattern.matches(EMAIL_PATTERN, (String)newValue)) {
                    Toast.makeText(getActivity().getApplicationContext(), "Invalid email address.\n"
                            +"Changes discarded", Toast.LENGTH_LONG).show();
                    emailAddress.setText(address);

                    return false;
                }

                return  true;
            }
        });


        //if we don't use the GPS the device don't capture any image
        GPSPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                Log.i(TAG, "onPreferenceChange(): GPS preference changed!");

                if ((boolean)newValue)
                    photoPreference.setEnabled(true);
                else
                    photoPreference.setEnabled(false);

                return true;
            }
        });
    }
}