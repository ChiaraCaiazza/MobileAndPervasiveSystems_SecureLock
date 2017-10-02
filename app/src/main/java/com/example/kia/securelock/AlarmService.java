package com.example.kia.securelock;



import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;
import com.example.kia.securelock.mailHandler.GmailSender;
import java.io.File;



/**
 * Created by Chiara Caiazza.
 */
public class AlarmService extends Service implements SensorEventListener {
    protected SensorManager sensorManager;
    protected static Sensor proximitySensor;
    protected static Sensor accelerometerSensor;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location globalLocation;

    private boolean locationUpdated;
    private boolean inSending = false;

    private int oldVolume;
    private int oldRingerMode;
    private float proximityInitialValue;
    private float[] accelerometerInitialValues;

    private final String TAG = "SecureLock.AlarmService";
    private final String ALARM_RINGTONE="pref_key_alarm_preferred_ringtone";
    private final String GPS_ENABLED_SWITCH = "pref_key_alarm_GPS";
    private final String PROXIMITY_ALARM_TRIGGER = "pref_key_alarm_trigger_proximity_switch";
    private final String ACCELEROMETER_ALARM_TRIGGER = "pref_key_alarm_trigger_accelerometer_switch";
    private final String EMAIL_RECEIVER = "pref_key_email_receiver";
    private final String PHOTO_ENABLE = "pref_key_alarm_photo";
    private final String TIME_TO_SEND = "pref_key_time_to_send";
    private String username;

    private SharedPreferences sharedPreferences;

    private final IBinder binder = new AlarmBinder();

    protected Ringtone r = null;

    private GmailSender gmailSender;

    private AlarmStateSingleton alarmStateSingleton;


    public AlarmService() {
        alarmStateSingleton = AlarmStateSingleton.getInstance();
    }


    public void onCreate() {
    // TODO: Actions to perform when service is created.
        Log.i(TAG, "onCreate()");
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        //check if the proximity sensor is present
        if (sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null) {
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            Log.i(TAG, "proximity sensor registered");
        }
        else
            proximitySensor = null;

        //check if the accelerometer sensor is present
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
            //the sensor is present
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);
            Log.i(TAG, "Accelerometer registered");
        }
        else
            //the sensor is not present
            accelerometerSensor = null;

        //sensing is not useful when the device is unlocked
        int res = stopSensing();
        if (res == -1)
            Log.i(TAG, "stopSensing() failed");

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {


            @Override
            public void onLocationChanged(Location location) {
                Log.i(TAG, "onLocationChanged()");
                String text = "lat: "+location.getLatitude()+" long: "+location.getLongitude();
                Log.i(TAG, text);

                globalLocation = location;
                //a new location is received
                locationUpdated = true;
            }


            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}


            @Override
            public void onProviderEnabled(String provider) {}


            @Override
            public void onProviderDisabled(String provider) {}
        };
    }


    public class AlarmBinder extends Binder {
        AlarmService getService(){
            return AlarmService.this;
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        // return null in “started” services
        return binder;
    }


    public int startSensing(){
        if (proximitySensor != null && sharedPreferences.getBoolean(PROXIMITY_ALARM_TRIGGER, false) )
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);

        if(accelerometerSensor != null && sharedPreferences.getBoolean(ACCELEROMETER_ALARM_TRIGGER, false))
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);

        //initialValues
        proximityInitialValue = -1;
        accelerometerInitialValues = null;

        return 1;
    }


    public int stopSensing(){
        Log.i(TAG, "in stopSensing()");
        sensorManager.unregisterListener(this);

        //stop to collect GPS measurements
        if (locationManager != null)
            locationManager.removeUpdates(locationListener);

        //if the device is playing a ringtone we stop it
        if (r != null && r.isPlaying()) {
           stopRing();
        }
        return 1;
    }


    public void onAccuracyChanged (Sensor sensor, int accuracy){}


    public void onSensorChanged (SensorEvent sensorEvent){
        if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY && proximityInitialValue == -1){
            //we take the first observation
            Log.i(TAG, "onSensorChanged: Proximity first observation " +
                            Float.toString(sensorEvent.values[0]));
            //first reading
            proximityInitialValue = sensorEvent.values[0];

            return;
        }

        if (MainActivity.requiredTime > SystemClock.elapsedRealtime())
            return;

        if (alarmStateSingleton.getAlarm() || !alarmStateSingleton.getLockState())
            return;

        if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER && accelerometerInitialValues == null){
            Log.i(TAG, "onSensorChanged: Accelerometer first observation " +
                    Float.toString(sensorEvent.values[0]) + ", " +
                    Float.toString(sensorEvent.values[1]) + ", " +
                    Float.toString(sensorEvent.values[2]));

            //first observation
            accelerometerInitialValues = new float[3];
            System.arraycopy(sensorEvent.values, 0, accelerometerInitialValues, 0, sensorEvent.values.length);

            return;
        }

        if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY){
            Log.i(TAG, "onSensorChanged: Proximity " + Float.toString(sensorEvent.values[0]));

            if (proximityInitialValue != sensorEvent.values[0]){
                //someone tooks the device!
                Log.i(TAG, "Someone tooks the device!\nThe alarm should rings");

                alarmBehaviour();
            }
        }
        else if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER){
          Log.i(TAG, "onSensorChanged: Accelerometer " +
                  Float.toString(sensorEvent.values[0]) + ", " +
                  Float.toString(sensorEvent.values[1]) + ", " +
                  Float.toString(sensorEvent.values[2]));

            for (int i = 0; i<accelerometerInitialValues.length; i++) {
                //absolute value of the difference between the actual sample and the last one
                float movement = Math.abs(accelerometerInitialValues[i] - sensorEvent.values[i]);
                //a minimum difference has to be allowed
                float noise = 2.0f;

                if (movement > noise){
                    //The computed difference is too much! The device was touched by someone!
                    Log.i(TAG, "Someone moved the device!\nThe alarm should rings");

                    alarmBehaviour();
                    break;
                }
            }
        }
    }


    public void alarmBehaviour (){
        //sensors are not required anymore
        sensorManager.unregisterListener(this);

        if (alarmStateSingleton.getAlarm())
            return;
        alarmStateSingleton.setAlarm(true);

        startRing();

        if (!isLocationEnabled()) {
            Log.i(TAG, "alarmSound(): unable to activate location services");
            return;
        }

        //check permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED){
            //we are not allowed to use the GPS
            Log.i(TAG, "checkSelfPermission() failed");
            return;
        }

        if (startLocate() == -1)
            Log.i(TAG, "startLocate() failed");

        if (!inSending)
        startSending();
    }


    private void startSending() {
        Log.i(TAG, "in startSending()");

        //every 3 minutes I try to send a new email
        new android.os.Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (alarmStateSingleton.getAlarm()) {
                    //I send every 3 minutes if the alarm is on
                    startSending();
                    inSending = true;
                }
                else
                    inSending=false;
            }
        }, 1000*60*Long.parseLong(sharedPreferences.getString(TIME_TO_SEND, "3")));


        if (!locationUpdated && !sharedPreferences.getBoolean(PHOTO_ENABLE, false))
            //the user don't want to take any photo and the location info are old.
            //I don't send any email
            return;

        if (!locationUpdated && alarmStateSingleton.getPictureCounter() == 0)
            //we have no photo available. (we also have no new information about location)
            //I don't send any email
            return;

        //send an email with the location
        username = sharedPreferences.getString("login_username", "");
        String password = sharedPreferences.getString("login_password", "");
        if (username.equals("") || password.equals("")){
            Log.i(TAG, "Auth. failed. Impossible to sent any email.");
            return;
        }

        gmailSender = new GmailSender(username, password);
        try {
            new MyAsyncClass().execute();
        } catch (Exception ex) {
            Log.i(TAG, "Problem in sending the email");
        }
    }


    class MyAsyncClass extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... mApi) {
            try {
                String subject = "SecureLock: Device detected";
                String body;

                if (locationUpdated)
                    body = "Your alarm is active and a new location was detected. Visit the " +
                            "last know position by clicking the following link.\n\n";
                else
                    body = "Your alarm is active but a new location was not found. Visit the " +
                            "last know position by clicking the following link.\n\n";

                body += "http://maps.google.com/maps?q=loc:"+globalLocation.getLatitude()
                        +","+globalLocation.getLongitude();
                locationUpdated = false;

                String receiver = sharedPreferences.getString(EMAIL_RECEIVER, "");
                String sender = username;

                if (receiver.equals("")){
                    Log.i(TAG, "null email receiver. Impossible to send the email");
                    return null;
                }


                if(alarmStateSingleton.getPictureCounter() == 0){
                    //we sent the email without attachment
                    gmailSender.sendMail(subject, body, sender, receiver);
                    Log.i(TAG, "sent without photo");
                }
                else {
                    //getting the most recent photo from the folder
                    String path = String.valueOf(Environment.getExternalStorageDirectory() +
                            "/SecureLock/" +
                            ((alarmStateSingleton.getPictureCounter() - 1) % 3) +
                            "_SecurityImage.jpg");

                    String newPath = String.valueOf(Environment.getExternalStorageDirectory()
                            + "/SecureLock/SecureLockSecurityImage.jpg");
                    final File file = new File(path);
                    final File newFile = new File(newPath);
                    if (!file.renameTo(newFile))
                        return null;

                    Log.i(TAG, "sent: " + path);
                    // Add subject, Body, your mail Id, and receiver mail Id.
                    gmailSender.sendMail(subject, body, sender, receiver, newFile);
                }
            }

            catch (Exception ex) {
                Log.i(TAG, "problem in sending the email");
            }
            Log.i(TAG, "email sent");
            return null;
        }
    }


    private int startLocate() {
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setPowerRequirement(Criteria.POWER_LOW);

        String provider = locationManager.getBestProvider(criteria, false);
        if(provider != null) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED)
                return -1;
            /*
              void requestLocationUpdates (String provider, long minTime, float minDistance,
                                           LocationListener listener)
              minTime is in millisecond, minDistance is in meter
            */
            long minTime = 2 * 60 *1000;        //2 min
            float minDistance = 0;
            locationManager.requestLocationUpdates(provider, minTime, minDistance, locationListener);
            Log.i(TAG, "locationManager.requestLocationUpdates()");

            Toast.makeText(this, "Best Provider is " + provider, Toast.LENGTH_LONG).show();
        }
        else
            Log.i(TAG, "provider == null");

        return 0;
    }


    private void startRing() {
        Uri uri;

        //when we lock the device we need to set the maximum volume
        AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        //save old values
        oldRingerMode = audioManager.getRingerMode();
        oldVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING);
        //set new values
        audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        audioManager.setStreamVolume(AudioManager.STREAM_RING,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_RING), AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);

        String strRingtonePreference = sharedPreferences.getString(ALARM_RINGTONE, "");
        if(strRingtonePreference.equals(""))
            //the user don't choose a ringtone
            // Set the alarm to this system's default alarm.
            uri = Settings.System.DEFAULT_ALARM_ALERT_URI;
        else
            uri = Uri.parse(strRingtonePreference);

        r = RingtoneManager.getRingtone(AlarmService.this, uri);
        r.play();
    }


    private void stopRing(){
        r.stop();

        try {
            //restore the old settings
            AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            audioManager.setRingerMode(oldRingerMode);
            audioManager.setStreamVolume(AudioManager.STREAM_RING, oldVolume, 0);
        }
        catch (SecurityException e){
            Log.i(TAG, "cannot change do not disturb mode");
        }
    }


    /**
     * the user may have its location's setting turned off.
     * check if location services are enabled
     */
    private boolean isLocationEnabled() {
        Log.i(TAG, "isLocationEnabled");

        if (!sharedPreferences.getBoolean(GPS_ENABLED_SWITCH, false)) {
            Log.i(TAG, "The user don't want to use the GPS");
            return false;
        }

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
            Log.i(TAG, "The GPS was already active");
            return true;
        }

        return false;
    }
}