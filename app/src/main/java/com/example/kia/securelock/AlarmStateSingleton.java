package com.example.kia.securelock;



/***
 * Created by Chiara Caiazza on 23/08/2017.
 */
public class AlarmStateSingleton {
    private static AlarmStateSingleton alarmStateSingleton = null;

    private boolean alarm = false;
    private boolean lockState;
    private boolean alertIsShowing;

    private int pictureCounter = 0;


    private AlarmStateSingleton(){
    }


    public static AlarmStateSingleton getInstance(){
        if(alarmStateSingleton == null) {
            alarmStateSingleton = new AlarmStateSingleton();
        }
        return alarmStateSingleton;
    }


    public boolean getAlarm(){
        return this.alarm;
    }
    public void setAlarm(Boolean value){
        alarm = value;
    }


    public boolean getLockState(){
        return lockState;
    }
    public void setLockState(Boolean value){
        lockState = value;
    }


    public int getPictureCounter(){
        return pictureCounter;
    }
    public void incrementPictureCounter(){
        pictureCounter++;
    }
    public void refreshPictureCounter(){
        pictureCounter = 0;
    }


    public boolean getAlertIsShowing() {
        return alertIsShowing;
    }
    public void setAlertIsShowing (boolean value){
        alertIsShowing = value;
    }
}
