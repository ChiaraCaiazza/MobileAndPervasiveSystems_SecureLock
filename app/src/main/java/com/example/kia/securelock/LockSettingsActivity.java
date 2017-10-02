package com.example.kia.securelock;



import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;



/***
 * Created by Chiara Caiazza.
 */
public class LockSettingsActivity extends AppCompatActivity {
    static final String TAG = "SL.LockSettingsActivity";
    private boolean enableGPS;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            enableGPS = intent.getBooleanExtra("EnableGPS", false);
        }

        setContentView(R.layout.activity_settings);
        initUpperToolbar();

        getFragmentManager().beginTransaction().replace(R.id.fragment_container,
                new SettingsPreferenceFragment()).commit();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.i(TAG, "onSaveInstanceState");

        outState.putBoolean("enable_gps", enableGPS);
    }

    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Log.i(TAG, "onRestoreInstanceState and saved state is " +
                (savedInstanceState == null ? "null" : "not null"));

        if (savedInstanceState != null)
            enableGPS = savedInstanceState.getBoolean("enable_gps");
    }


    protected void initUpperToolbar() {
        Toolbar upperToolbar;
        ImageView settingsButton, homeButton, logButton;

        upperToolbar = (Toolbar) findViewById(R.id.settings_toolbar);
        setSupportActionBar(upperToolbar);

        if (getSupportActionBar()!=null)
            //remove the title from the upper toolbar
            getSupportActionBar().setDisplayShowTitleEnabled(false);

        settingsButton = (ImageView) findViewById(R.id.settings_button);
        homeButton = (ImageView) findViewById(R.id.home_button);
        logButton = (ImageView) findViewById(R.id.log_button);

        settingsButton.setVisibility(View.INVISIBLE);
        logButton.setVisibility(View.INVISIBLE);
        homeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
    }
}