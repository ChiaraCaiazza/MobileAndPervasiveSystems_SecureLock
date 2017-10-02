package com.example.kia.securelock;



import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;



/***
 * Created by Chiara Caiazza.
 */
public class MonitorPowering extends BroadcastReceiver {
    private static final String TAG = "SecLock.MonitoringPower";
    private AlarmStateSingleton alarmStateSingleton = AlarmStateSingleton.getInstance();


    @Override
    public void onReceive(Context context, Intent intent) {
        // TODO: This method is called when the BroadcastReceiver is receiving

        if(intent.getAction().equals(Intent.ACTION_POWER_DISCONNECTED) &&
                alarmStateSingleton.getLockState()){
            //someone unplugged  the device and the alarm is on.
            Log.i(TAG, "Power disconnected");

            Intent myIntent = new Intent(context, MainActivity.class);
            myIntent.putExtra("ACTION_POWER_DISCONNECTED", true);
            context.startActivity(myIntent);
        }
    }
}