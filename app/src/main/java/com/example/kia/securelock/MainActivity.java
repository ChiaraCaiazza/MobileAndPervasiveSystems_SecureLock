package com.example.kia.securelock;



import android.Manifest;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import com.example.kia.securelock.Camera.CapturePictureInterface;
import com.example.kia.securelock.Camera.CapturePicture;
import com.example.kia.securelock.Camera.PictureCapturingServiceImpl;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;



/***
 * Created by Chiara Caiazza.
 */
public class MainActivity extends AppCompatActivity  implements CapturePictureInterface, ActivityCompat.OnRequestPermissionsResultCallback {
    private final String TAG = "SecureLock.MainActivity";
    private final String PROXIMITY_ALARM_TRIGGER = "pref_key_alarm_trigger_proximity_switch";
    private final String ACCELEROMETER_ALARM_TRIGGER = "pref_key_alarm_trigger_accelerometer_switch";
    private final String CHARGING_ALARM_TRIGGER = "pref_key_alarm_trigger_charging_switch";
    private final String GPS_ENABLE = "pref_key_alarm_GPS";
    private final String PHOTO_ENABLE = "pref_key_alarm_photo";
    private final int REQUEST_ALL_PERMISSIONS = 1;
    private final int REQUEST_GPS_TURN_ON = 2;
    private final int REQUEST_LOCATION_PERMISSION = 3;
    private final int REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS = 4;
    private final int MY_REQUEST_ALL_PERMISSIONS = 5;

    private boolean onRequesting;
    private boolean askPermissions;
    private static boolean askPIN = false;
    private boolean connected;
    private boolean startPhoto = true;

    private int rotationState;
    protected static long requiredTime;
    protected long loopTime;

    private SharedPreferences sharedPreferences;

    private Toast toast;

    private static AlertDialog alertDialog = null;

    private static Intent alarmServiceIntent = null;

    private static AlarmService alarmService;
    private AlarmStateSingleton alarmStateSingleton;

    private ImageView imageLockState;

    //service
    private CapturePicture pictureService;

    private KeyStore keyStore;
    // Variable used for storing the key in the Android Keystore container
    private static final String KEY_NAME = "SecureLock";
    private Cipher cipher;


    public MainActivity() {
        alarmStateSingleton = AlarmStateSingleton.getInstance();
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int res;
        //alarmStateSingleton.setLockPressed(false);

        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");

        res = restoreVariable(savedInstanceState);
        if (res != -1)
            Log.i(TAG, "restoreVariable(): DONE");
        else
            Log.i(TAG, "restoreVariable(): FAILED");



        //check if the power cable was disconnected
        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            if (intent.getBooleanExtra("ACTION_POWER_DISCONNECTED", false)) {
                intent.removeExtra("ACTION_POWER_DISCONNECTED");
                //the power cable was disconnected
                if (sharedPreferences.getBoolean(CHARGING_ALARM_TRIGGER, false) && alarmStateSingleton.getLockState()) {
                    //the alarm should startRing only if it is activated and the user wants to monitor the
                    //powering state
                    Log.i(TAG, "Someone unplugged the device!\nThe alarm should rings");
                    alarmService.alarmBehaviour();
                }
            }
        }

        //orientation of the screen (landscape/portrait)
        rotationState = MainActivity.this.getResources().getConfiguration().orientation;

        //inflate the user interface
        setContentView(R.layout.activity_main);

        //initialize the upper toolbar
        initUpperToolbar(this);

        defineLockButtonBehaviour();

        if (savedInstanceState == null) {
            //the user open the time for the first time
            checkAndRequestPermissions();
        }

        pictureService = PictureCapturingServiceImpl.getInstance(this);
    }


    private void defineLockButtonBehaviour() {
        //define onClick() behaviour
        imageLockState = (ImageView) findViewById(R.id.image_lock_state);

        imageLockState.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                //lock_state == false means that the device is unlocked
                if (!alarmStateSingleton.getLockState()) {
                    //lock the device
                    askPermissions = false;

                    if (!checkTrigger())
                        //there is no alarm trigger available
                        return;
                    //check if audio is in silent mode and eventually ask to remove this setting
                    if (checkAudioSettings() == -1)
                        return;

                    if (sharedPreferences.getBoolean(GPS_ENABLE, true)) {
                        //the use wants to use the GPS

                        // check if the email parameter are properly setted
                        if (!checkSenderReceiver())
                            return;

                        //check if location permission is provided
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                                != PackageManager.PERMISSION_GRANTED) {
                            //we have no location permission
                            askForGPS(REQUEST_LOCATION_PERMISSION);
                            return;
                        }

                        //check if the GPS is on
                        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
                        if (!alarmStateSingleton.getAlertIsShowing() &&
                                !(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                                        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))) {
                            //GPS is disabled
                            askForGPS(REQUEST_GPS_TURN_ON);
                            return;
                        }

                        //check if there is a dialog open
                        if (askPermissions)
                            //dialog for REQUEST_LOCATION_PERMISSION is still open
                            return;

                        askForPermissions(REQUEST_ALL_PERMISSIONS);
                    }
                    if (!askPermissions) {
                        //there are no dialog open
                        //we can lock the device

                        //the device is locked
                        alarmStateSingleton.setLockState(true);

                        if  (sharedPreferences.getBoolean(GPS_ENABLE, true)) {
                            //we make photo only of localization is enabled (and emails can be sent)
                            if (toast != null)
                                //delete the old toast
                                toast.cancel();

                            String text = "Starting capture!";
                            toast = Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT);
                            toast.show();

                            //start the capture sessions (photo will be taken only if the alarm is on)
                            if (sharedPreferences.getBoolean(PHOTO_ENABLE, true))
                                pictureService.startCapturing(MainActivity.this);
                        }

                        //we can lock the device
                        lockDevice(imageLockState);
                    }
                }
                else {
                    //unlock the device
                    startUnlock();
                }
            }

            private int checkAudioSettings() {
                int res;

                AudioManager audioManager = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                if (audioManager.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
                    //if the phone is in silent mode
                    res = -1;

                    final AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    String title = "Silent mode detected";
                    String message = "The alarm cannot work in silent mode. Do you want to remove silent mode?";

                    builder.setMessage(message)
                            .setTitle(title)
                            .setPositiveButton("OK",
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int id) {
                                            alertDialog.dismiss();
                                            Intent intent = new Intent(Settings.ACTION_SOUND_SETTINGS);
                                            alarmStateSingleton.setAlertIsShowing(false);
                                            startActivity(intent);
                                        }
                                    })
                            .setNegativeButton("Cancel",
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int id) {
                                            //alarmStateSingleton.setLockPressed(false);
                                            alertDialog.cancel();
                                        }
                                    });
                    alertDialog = builder.create();
                    alertDialog.show();

                } else
                    //the phone isn't silent
                    res = 0;

                return res;
            }
        });
    }


    private boolean checkTrigger() {
        //start sensing. we need to use ONLY the sensors selected by the user.
        if (sharedPreferences.getBoolean(PROXIMITY_ALARM_TRIGGER, false) ||
                sharedPreferences.getBoolean(ACCELEROMETER_ALARM_TRIGGER, false) ||
                sharedPreferences.getBoolean(CHARGING_ALARM_TRIGGER, false)) {
            return true;
        }

        //there is no alarm trigger setted
        String text = "There are no alarm trigger setted. Please customize your settings before " +
                "locking your device";
        if (toast != null)
            //delete the old toast
            toast.cancel();

        toast = Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT);
        toast.show();

        return false;
    }


    private boolean checkSenderReceiver() {
        String text ="";

        //start sensing. we need to use ONLY the sensors selected by the user.
        if (sharedPreferences.getString("login_username", "").equals("") ||
                sharedPreferences.getString("login_password", "").equals(""))
            text = "Authentication failed: You choose to receive GPS measurement but a login is " +
                   "required.\nPlease perform the login before locking your device (or change your " +
                   "settings)";

        if (sharedPreferences.getString("pref_key_email_receiver", "").equals("")){
            text = "You choose to receive GPS measurement but there isn't any email receiver. Please" +
                    " customize your settings before locking your device";
        }

        if (text.equals(""))
            return true;

        if (toast != null)
            //delete the old toast
            toast.cancel();

        toast = Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT);
        toast.show();

        return false;
    }


    private int restoreVariable(Bundle savedInstanceState) {
        //triggers variables
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        if (savedInstanceState != null) {
            askPermissions = savedInstanceState.getBoolean("ask_gps");
            onRequesting = savedInstanceState.getBoolean("on_requesting", false);
            startPhoto = savedInstanceState.getBoolean("start_photo", false);

            loopTime = SystemClock.elapsedRealtime();
            return 0;
        }
        return -1;
    }


    private void lockDevice(ImageView imageLockState) {
        long delay;

        alarmStateSingleton.setLockState(true);

        //start sensing. we need to use ONLY the sensors selected by the user.
        Log.i(TAG, "lockDevice - proximity option: "
                + sharedPreferences.getBoolean(PROXIMITY_ALARM_TRIGGER, false));
        Log.i(TAG, "lockDevice - accelerometer option: "
                + sharedPreferences.getBoolean(ACCELEROMETER_ALARM_TRIGGER, false));

        //start AlarmService
        alarmService.startSensing();

        delay = 5 * 1000; //5 seconds
        requiredTime = SystemClock.elapsedRealtime() + delay;

        //change the image programmatically
        imageLockState.setImageResource(R.drawable.image_lock_chiuso);
    }


    private void unlockDevice(ImageView imageLockState) {
        Log.i(TAG, "unlockDevice()");

        //change the image programmatically
        imageLockState.setImageResource(R.drawable.image_lock_aperto);

        //unlock the device
        alarmStateSingleton.setLockState(false);
        alarmStateSingleton.setAlarm(false);
        alarmStateSingleton.refreshPictureCounter();

        alarmService.stopSensing();

        //unbind the service
        if (connected) {
            unbindService(conn);
            connected = false;
        }
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Log.i(TAG, "dispatchKeyEvent()");
        String text;

        if (alarmStateSingleton.getLockState()) {
            //if (lock_state) {
            //every physical button has to be blocked if the device is locked
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    text = "Back button disabled. Unlock the device first.";
                    break;
                case KeyEvent.KEYCODE_VOLUME_UP:
                    text = "Volume up button disabled. Unlock the device first.";
                    break;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    text = "Volume down button disabled. Unlock the device first.";
                    break;
                default:
                    text = "Button disabled. Unlock the device first.";
            }

            if (toast != null)
                //delete the old toast
                toast.cancel();

            toast = Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT);
            toast.show();
            return true;
        }

        //if the device is not blocked we have to use the standard behaviour
        return super.dispatchKeyEvent(event);
    }


    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart");
    }


    @Override
    protected void onRestart() {
        super.onRestart();
        Log.i(TAG, "onRestart");
    }


    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");

        //make sure to show the proper image
        if (alarmStateSingleton.getLockState()) {
            ImageView lock_image = (ImageView) findViewById(R.id.image_lock_state);
            lock_image.setImageResource(R.drawable.image_lock_chiuso);
        }

        if (alarmServiceIntent == null && !onRequesting) {
            //AlarmService starts
            alarmServiceIntent = new Intent(this, AlarmService.class);
            bindService(alarmServiceIntent, conn, MainActivity.this.getApplicationContext().BIND_AUTO_CREATE);
        }

        if (alertDialog != null && alarmStateSingleton.getAlertIsShowing())
            //we have to show a previously dismissed dialog because the user  has not yet made a choice
            alertDialog.show();
    }


    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause");

        //unbind the service
        if (connected) {
            unbindService(conn);
            connected = false;
            Log.i(TAG, "unbind()");
        }

        //lock state == true means that the device is locked
        if (alarmStateSingleton.getLockState()
              && MainActivity.this.getResources().getConfiguration().orientation == rotationState) {
            //in lock_state. onPause is not called sue to screen rotation
            bringApplicationToFront();
        }

        if (myDialog!=null)
            myDialog.dismiss();
    }


    private void bringApplicationToFront() {

        if (askPIN){
            askPIN = false;
            return;
        }

        DisplayManager dm = (DisplayManager) this.getSystemService(Context.DISPLAY_SERVICE);

        for (Display display : dm.getDisplays())
            if (display.getState() == Display.STATE_OFF) {
                Log.i(TAG, "Screen off");
                return;
            }

        if (toast != null)
            //delete the old toast
            toast.cancel();
        toast = Toast.makeText(MainActivity.this, "BringApplicationToFront", Toast.LENGTH_SHORT);
        toast.show();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        //LAG_ACTIVITY_CLEAR_TOP: If the activity launched is already running in this task,
        // then all of the other activities on top of it will be closed. This Intent will be
        // delivered to the current instance's onNewIntent()
        //FLAG_ACTIVITY_SINGLE_TOP: If set, the activity will not be launched if it is already
        // running at the top of the stack.
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        try {
            pendingIntent.send();
        } catch (PendingIntent.CanceledException e) {
            Log.i(TAG, "PendingIntent.CanceledException");
        }
    }


    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop");
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");

        if (alertDialog != null && alertDialog.isShowing())
            alertDialog.dismiss();
    }


    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Log.i(TAG, "onRestoreInstanceState and saved state is " +
                (savedInstanceState == null ? "null" : "not null"));
    }


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.i(TAG, "onSaveInstanceState");


        if (alertDialog != null && alertDialog.isShowing())
            alarmStateSingleton.setAlertIsShowing(true);
        else
            alarmStateSingleton.setAlertIsShowing(false);

        outState.putBoolean("ask_gps", askPermissions);
        outState.putBoolean("on_requesting", onRequesting);
        outState.putBoolean("start_photo", startPhoto);
    }


    /**
     * initialize the upper toolbar
     */
    protected void initUpperToolbar(final MainActivity mainActivity) {
        Log.i(TAG, "initUpperToolbar()");
        Toolbar upperToolbar;
        ImageView settingsButton, homeButton, logButton;

        upperToolbar = (Toolbar) findViewById(R.id.main_toolbar);
        setSupportActionBar(upperToolbar);

        //remove the title fron the upper toolbar
        //setDisplayShowTitleEnabled() may throw 'java.lang.NullPointerException' 
        if(getSupportActionBar()!= null)
            getSupportActionBar().setDisplayShowTitleEnabled(false);

        settingsButton = (ImageView) findViewById(R.id.settings_button);
        homeButton = (ImageView) findViewById(R.id.home_button);
        logButton = (ImageView) findViewById(R.id.log_button);

        homeButton.setVisibility(View.INVISIBLE);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (alarmStateSingleton.getLockState()) {
                    //the device is locked. we cannot start a different activity
                    String text = "Device Locked: unlock the device first.";

                    if (toast != null)
                        //delete the old toast
                        toast.cancel();
                    toast = Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT);
                    toast.show();

                    return;
                }

                Intent intent = new Intent(mainActivity, LockSettingsActivity.class);

                startActivity(intent);
            }
        });

        logButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (alarmStateSingleton.getLockState()) {
                    String text = "Device Locked: unlock the device first.";

                    if (toast != null)
                        //delete the old toast
                        toast.cancel();
                    toast = Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT);
                    toast.show();

                    return;
                }

                callLoginDialog();
            }
        });
    }

    Dialog myDialog = null;
    private void callLoginDialog() {
        myDialog = new Dialog(this);
        myDialog.setContentView(R.layout.login);
        myDialog.setCancelable(false);
        Button loginButton = (Button) myDialog.findViewById(R.id.login_Button);
        Button loginClose = (Button) myDialog.findViewById(R.id.login_Close);

        final EditText emailAddress = (EditText) myDialog.findViewById(R.id.login_insert_username);
        final EditText password = (EditText) myDialog.findViewById(R.id.login_insert_password);
        //eventually show the old value
        emailAddress.setText(sharedPreferences.getString("login_username", ""));
        password.setText(sharedPreferences.getString("login_password", ""));

        myDialog.show();

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String EMAIL_PATTERN= "^[_A-Za-z0-9]+(\\.[_A-Za-z0-9-]+)*@[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";
                Log.i(TAG, "save login info");

                //if no text return
                if (emailAddress.getText().toString().equals("")) {
                    if (toast != null)
                        //eventually delete the old toast
                        toast.cancel();

                    toast = Toast.makeText(getApplicationContext(), "Remember to provide valid " +
                            "authentication info.", Toast.LENGTH_SHORT);
                    toast.show();

                    return;
                }

                // pattern doesn't match so returning false
                if (!Pattern.matches(EMAIL_PATTERN, emailAddress.getText().toString())) {
                    if (toast != null)
                        //delete the old toast
                        toast.cancel();

                    toast = Toast.makeText(getApplicationContext(), "Invalid email address.\n"
                            +"Changes discarded", Toast.LENGTH_SHORT);
                    toast.show();

                    return;
                }

                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("login_username", emailAddress.getText().toString());
                editor.putString("login_password", password.getText().toString());
                editor.commit();

                myDialog.dismiss();
            }
        });

        loginClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                myDialog.cancel();
            }
        });
    }


    public ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "onServiceConnected()");
            AlarmService.AlarmBinder binder = (AlarmService.AlarmBinder) service;
            alarmService = binder.getService();
            connected = true;
        }


        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "onServiceDisconnected()");
            connected = false;
        }
    };


    protected void checkAndRequestPermissions() {
        Log.i(TAG, "checkAndRequestPermissions()");

        String[] permissions = new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION};

        int result;
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            result = ContextCompat.checkSelfPermission(this, p);
            if (result != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            //require the permissions
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[listPermissionsNeeded.size()]), MY_REQUEST_ALL_PERMISSIONS);

            onRequesting = true;
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                    @NonNull String permissions[], @NonNull int[] grantResults) {
        Log.i(TAG, "onRequestPermissionsResponse()");
        switch (requestCode) {
            case MY_REQUEST_ALL_PERMISSIONS:
                onRequesting = false;
                String message = "";

                if (grantResults.length > 0) {
                    if (!(ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED))
                        //permission not obtained. I cannot use the GPS
                        message = message + "\n- Without the permission to use the GPS you will " +
                                "not able to receive the coordinate of your device.";

                    if (ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                        //permission not obtained. I cannot use the GPS
                        message = message + "\n -Without the permission to use camera and save the "
                                +"into the memory you will not able to receive photos from your " +
                                "device.";

                    Log.i(TAG, "in onRequestPermissionsResult() permission refused");

                    if (!message.equals("")) {
                        message = "Remember that:" + message;
                        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
                        alertDialogBuilder.setTitle("Pay attention")
                                .setMessage(message)
                                .setNeutralButton("Ok", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface paramDialogInterface, int paramInt) {
                                        alertDialog.dismiss();
                                        alarmStateSingleton.setAlertIsShowing(false);
                                    }
                                });
                        alertDialog = alertDialogBuilder.create();
                        alertDialog.show();
                    }
                }
        }
    }


    public void  askForGPS(final int type){
        final AlertDialog.Builder builder =  new AlertDialog.Builder(MainActivity.this);
        final String action;
        String message, title;
        final Uri uri = Uri.fromParts("package", getPackageName(), null);

        switch (type){
            case REQUEST_LOCATION_PERMISSION:
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS;
                title = "Missing permission";
                message = "Do you want allow the application to acquire location permission?";
                break;
            case REQUEST_GPS_TURN_ON:
                action = Settings.ACTION_LOCATION_SOURCE_SETTINGS;
                title = "GPS is turned off";
                message = "Do you want to open GPS setting?";
                break;
            default:
                return;
        }
        askPermissions = true;

        builder.setMessage(message)
                .setTitle(title)
                .setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int id) {
                                final Intent intent = new Intent(action);

                                if (type == REQUEST_LOCATION_PERMISSION) {
                                    intent.setData(uri);
                                    askPermissions = false;
                                }
                                alertDialog.dismiss();
                                alarmStateSingleton.setAlertIsShowing (false);
                                startActivity(intent);
                            }
                        })
                .setNegativeButton("No",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int id) {
                                alarmStateSingleton.setAlertIsShowing (false);
                                alertDialog.cancel();
                            }
                        });
        alertDialog = builder.create();
        alertDialog.show();
    }


    public void askForPermissions(final int type) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        final String action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS;
        String message = "";
        final Uri uri = Uri.fromParts("package", getPackageName(), null);

        switch (type) {
            case REQUEST_ALL_PERMISSIONS:
                message = "Do you want allow the application to acquire ";
                boolean requestCamera = false;
                boolean requestWrite = false;

                if (ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                    requestCamera = true;

                    if (ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                        requestWrite = true;

                if (!requestCamera && !requestWrite) {
                    //all the required permissions are granted
                    askPermissions = false;
                    return;
                }

                if (requestCamera && requestWrite)
                //we need to ask camera permission
                    message = message + " camera and ";
                if (requestCamera && !requestWrite)
                    //we need to ask camera permission
                    message = message + " camera ";
                if (requestWrite)
                    message = message + "write";

                if (requestCamera && requestWrite)
                    message = message + " permissions?";
                else
                    message = message + " permission?";

                break;
        }
        askPermissions = true;

        builder.setMessage(message)
                .setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int id) {
                                final Intent intent = new Intent(action);

                                if (type == REQUEST_ALL_PERMISSIONS) {
                                    intent.setData(uri);
                                    askPermissions = false;
                                }
                                alertDialog.dismiss();
                                alarmStateSingleton.setAlertIsShowing(false);
                                startActivity(intent);
                            }
                        })
                .setNegativeButton("No",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int id) {
                                lockDevice(imageLockState);
                                alertDialog.cancel();
                            }
                        });
        alertDialog = builder.create();
        alertDialog.show();
    }


    /**
     * We've finished taking pictures from all phone's cameras
     */
    @Override
    public void onDoneCapturingAllPhotos(TreeMap<String, byte[]> picturesTaken) {
        String text;

        if (picturesTaken != null && !picturesTaken.isEmpty())
            return;

        text = "No camera detected!";

        if (toast != null)
            //delete the old toast
            toast.cancel();

        toast = Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT);
        toast.show();
    }


    @Override
    public void onCaptureDone(final String pictureUrl, final byte[] pictureData) {
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        Log.i(TAG, "onWindowFocusChanged()");

        //notification bar has to be closed
        if (!hasFocus && alarmStateSingleton.getLockState()) {

            //I try to close the bar every 200 millisecond for 5 second
            for (int i = 1; i<=25; i++) {
                new android.os.Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Object service = getSystemService("statusbar");
                            Class<?> statusbarManager = Class
                                    .forName("android.app.StatusBarManager");

                            Method collapse = statusbarManager.getMethod("collapsePanels");
                            collapse.setAccessible(true);
                            collapse.invoke(service);

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }, 200 * i);
            }
        }
    }



    @RequiresApi(api = Build.VERSION_CODES.M)
    public void startUnlock(){
        final KeyguardManager keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        final AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        final FingerprintManager fingerprintManager = (FingerprintManager) getSystemService(FINGERPRINT_SERVICE);

        boolean useFingerprint = false;
        String title = "Device locked";
        String message;

        // Check whether the device has a Fingerprint sensor.
        if(fingerprintManager.isHardwareDetected()){
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.USE_FINGERPRINT) == PackageManager.PERMISSION_GRANTED){
                //permission acquired
                // Check whether at least one fingerprint is registered
                if (!fingerprintManager.hasEnrolledFingerprints()) {
                    Log.i(TAG, "Register at least one fingerprint in settings");
                    useFingerprint = false;
                }else{
                    useFingerprint = true;
                }
            }
            else
                useFingerprint = false;
        }

        //we have a fingerprint sensor and at least one fingerprint is registered
        message = "Insert your fingerprint to unlock the device";

        builder.setMessage(message)
                .setTitle(title);
        if(useFingerprint)
            //we have the proper hw and at least one fingerprint is registered
            builder.setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int id) {
                                generateKey();

                                if (cipherInit()) {
                                    FingerprintManager.CryptoObject cryptoObject = new FingerprintManager.CryptoObject(cipher);
                                    FingerprintHandler helper = new FingerprintHandler(MainActivity.this);
                                    helper.startAuth(fingerprintManager, cryptoObject);
                                }

                                alertDialog.dismiss();
                            }
                        });

        builder.setNeutralButton("Use PIN",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int id) {
                                alertDialog.dismiss();
                                askPIN = true;
                                Intent intent = keyguardManager.createConfirmDeviceCredentialIntent(null, null);
                                if (intent != null) {
                                    startActivityForResult(intent, REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS);
                                }
                                else {
                                    //the user have no PIN in the device. This is extremely dangerous
                                    //and should not happen.
                                    askPIN = false;
                                    alertDialog.dismiss();
                                    unlockDevice(imageLockState);
                                }
                            }
                        });

        alertDialog = builder.create();
        alertDialog.show();
    }


    @TargetApi(Build.VERSION_CODES.M)
    protected void generateKey() {
        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
        } catch (Exception e) {
            e.printStackTrace();
        }

        KeyGenerator keyGenerator;
        try {
            keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new RuntimeException("Failed to get KeyGenerator instance", e);
        }

        try {
            keyStore.load(null);
            keyGenerator.init(new
                    KeyGenParameterSpec.Builder(KEY_NAME,
                    KeyProperties.PURPOSE_ENCRYPT |
                            KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setUserAuthenticationRequired(true)
                    .setEncryptionPaddings(
                            KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build());
            keyGenerator.generateKey();
        } catch (NoSuchAlgorithmException |
                InvalidAlgorithmParameterException
                | CertificateException | IOException e) {
            throw new RuntimeException(e);
        }
    }


    @TargetApi(Build.VERSION_CODES.M)
    public boolean cipherInit() {
        try {
            cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_CBC + "/" + KeyProperties.ENCRYPTION_PADDING_PKCS7);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new RuntimeException("Failed to get Cipher", e);
        }

        try {
            keyStore.load(null);
            SecretKey key = (SecretKey) keyStore.getKey(KEY_NAME,
                    null);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return true;
        } catch (KeyPermanentlyInvalidatedException e) {
            return false;
        } catch (KeyStoreException | CertificateException | UnrecoverableKeyException | IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to init Cipher", e);
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS) {
            // Challenge completed, proceed with using cipher
            if (resultCode == RESULT_OK) {
                if (toast != null)
                    //delete the old toast
                    toast.cancel();

                toast = Toast.makeText(getApplicationContext(), "PIN accepted. The device will be " +
                        "unlocked.", Toast.LENGTH_SHORT);
                toast.show();

                unlockDevice(imageLockState);
            } else {
                // The user canceled or didn’t complete the lock screen
                // operation. Go to error/cancellation flow.
                if (toast != null)
                    //delete the old toast
                    toast.cancel();

                toast = Toast.makeText(getApplicationContext(), "Authentication failed", Toast.LENGTH_SHORT);
                toast.show();

                askPIN = false;
            }
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.M)
    private class FingerprintHandler extends FingerprintManager.AuthenticationCallback {

        private Context context;
        private final static String TAG = "SL.FingerPrintHandler";

        // Constructor
        public FingerprintHandler(Context mContext) {
            context = mContext;
        }

        public void startAuth(FingerprintManager manager, FingerprintManager.CryptoObject cryptoObject) {
            CancellationSignal cancellationSignal = new CancellationSignal();
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.USE_FINGERPRINT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //required at least API 23
                manager.authenticate(cryptoObject, cancellationSignal, 0, this, null);
            }
        }


        @Override
        public void onAuthenticationError(int errMsgId, CharSequence errString) {
            Log.i(TAG, "onAuthenticationError()");
            if (toast != null)
                //delete the old toast
                toast.cancel();

            toast = Toast.makeText(getApplicationContext(), "Too many failed attempts\nStop to " +
                    "waiting for a fingerprint.", Toast.LENGTH_SHORT);
            toast.show();
        }


        @Override
        public void onAuthenticationHelp(int helpMsgId, CharSequence helpString) {
        }


        @Override
        public void onAuthenticationFailed() {
            Log.i(TAG, "onAuthenticationFailed()");
            if (toast != null)
                //delete the old toast
                toast.cancel();

            toast = Toast.makeText(getApplicationContext(), "Fingerprint not recognized", Toast.LENGTH_SHORT);
            toast.show();

            askPIN = false;
        }


        @Override
        public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
            Log.i(TAG, "onAuthenticationSucceeded()");

            if (toast != null)
                //delete the old toast
                toast.cancel();

            toast = Toast.makeText(getApplicationContext(), "Fingerprint recognized.\nDevice " +
                    "unlocked.", Toast.LENGTH_SHORT);
            toast.show();
            askPIN = false;
            alertDialog.dismiss();
            unlockDevice(imageLockState);
            imageLockState.setImageResource(R.drawable.image_lock_aperto);
            recreate();
        }
    }
}