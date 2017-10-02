package com.example.kia.securelock.Camera;



import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import com.example.kia.securelock.AlarmStateSingleton;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;



@TargetApi(Build.VERSION_CODES.LOLLIPOP) //NOTE: camera 2 api was added in API level 21
public class PictureCapturingServiceImpl extends CapturePicture {

    private static final String TAG = "SL.PictureCapturingServ";
    private final String PHOTO_ENABLE = "pref_key_alarm_photo";
    private final String GPS_ENABLE = "pref_key_alarm_GPS";
    private boolean loopActive = false;

    private SharedPreferences sharedPreferences;
    private static Activity main;


    private AlarmStateSingleton alarmStateSingleton = AlarmStateSingleton.getInstance();


    private CameraDevice cameraDevice;
    private ImageReader imageReader;


    private String currentCameraId;
    private boolean cameraClosed;
    /**
     * stores a sorted map of (pictureUrlOnDisk, PictureData).
     */
    private TreeMap<String, byte[]> picturesTaken;
    private CapturePictureInterface capturingListener;

    /***
     * private constructor, meant to force the use of {@link #getInstance}  method
     */
    private PictureCapturingServiceImpl(final Activity activity) {
        super(activity);
    }

    /**
     * @param activity the activity used to get the app's context and the display manager
     * @return a new instance
     */
    public static CapturePicture getInstance(final Activity activity) {
        main = activity;
        return new PictureCapturingServiceImpl(activity);
    }

    /**
     * starts pictures capturing process.
     *
     * @param listener picture capturing listener
     */
    public void startCapturing(final CapturePictureInterface listener) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main);

        //the user don't want to take any picture!
        if (!sharedPreferences.getBoolean(GPS_ENABLE, true) &&
                !sharedPreferences.getBoolean(PHOTO_ENABLE, true))
            return;

        if (!alarmStateSingleton.getLockState())
            return;

        if (!alarmStateSingleton.getAlarm()) {
            // if the alarm is silent we take no photo
            Log.i(TAG, "failed to start a capture session (Alarm inactive) Retry later.");
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!loopActive)
                        startCapturing(listener);
                }
            }, 40 * 1000);
            return;
        }

        this.picturesTaken = new TreeMap<>();
        this.capturingListener = listener;
        try {
            final String[] cameraIds = manager.getCameraIdList();
            if (cameraIds.length > 0) {
                this.currentCameraId = "0";
                openCamera();
            } else {
                //No camera detected!
                capturingListener.onDoneCapturingAllPhotos(picturesTaken);
            }
        } catch (final CameraAccessException e) {
            Log.e(TAG, "Exception occurred while accessing the list of cameras", e);
        }
    }

    private void openCamera() {
        Log.d(TAG, "opening camera " + currentCameraId);
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                manager.openCamera(currentCameraId, stateCallback, null);
            }
        } catch (final CameraAccessException e) {
            Log.e(TAG, " exception occurred while opening camera " + currentCameraId, e);
        }
    }

    private final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            if (picturesTaken.lastEntry() != null) {
                capturingListener.onCaptureDone(picturesTaken.lastEntry().getKey(), picturesTaken.lastEntry().getValue());
                Log.i(TAG, "done taking picture from camera " + cameraDevice.getId());
            }
            closeCamera();
        }
    };


    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imReader) {
            final Image image = imReader.acquireLatestImage();
            final ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            final byte[] bytes = new byte[buffer.capacity()];
            buffer.get(bytes);
            PictureCapturingServiceImpl.this.saveImageToDisk(bytes);
            image.close();
        }
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraClosed = false;
            Log.d(TAG, "camera " + camera.getId() + " opened");
            cameraDevice = camera;
            Log.i(TAG, "Taking picture from camera " + camera.getId());
            //Take the picture after some delay. It may resolve getting a black dark photos.
            try {
                if (alarmStateSingleton.getAlarm())
                    takePicture();
            } catch (final CameraAccessException e) {
                Log.e(TAG, " exception occurred while taking picture from " + currentCameraId, e);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, " camera " + camera.getId() + " disconnected");
            if (cameraDevice != null && !cameraClosed) {
                cameraClosed = true;
                cameraDevice.close();
            }
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            cameraClosed = true;
            Log.d(TAG, "camera " + camera.getId() + " closed");
            //once the current camera has been closed, start taking another picture
            if (alarmStateSingleton.getAlarm()) {
                takeAnotherPicture();
            } else {
                capturingListener.onDoneCapturingAllPhotos(picturesTaken);
            }
        }


        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "camera in error, int code " + error);
            if (cameraDevice != null && !cameraClosed) {
                cameraDevice.close();
            }
        }
    };


    private void takePicture() throws CameraAccessException {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        if (!alarmStateSingleton.getAlarm()) {
            return;
        }
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
        Size[] jpegSizes = null;
        StreamConfigurationMap streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (streamConfigurationMap != null) {
            jpegSizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG);
        }
        final boolean jpegSizesNotEmpty = jpegSizes != null && 0 < jpegSizes.length;
        int width = jpegSizesNotEmpty ? jpegSizes[0].getWidth() : 640;
        int height = jpegSizesNotEmpty ? jpegSizes[0].getHeight() : 480;
        final ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
        final List<Surface> outputSurfaces = new ArrayList<>();
        outputSurfaces.add(reader.getSurface());
        final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.addTarget(reader.getSurface());
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF);
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation());
        //autofocus
        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
        //auto exposure
        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        //auto whiteBalance
        captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        reader.setOnImageAvailableListener(onImageAvailableListener, null);
        cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        try {
                            if (alarmStateSingleton.getAlarm())
                                session.capture(captureBuilder.build(), captureListener, null);
                            else {
                                session.close();
                                closeCamera();
                            }
                        } catch (CameraAccessException e) {
                            Log.e(TAG, " exception occurred while accessing " + currentCameraId, e);
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    }
                }
                , null);
    }


    private void saveImageToDisk(final byte[] bytes) {
        //create my folder
        boolean success = true;

        File folder = new File(Environment.getExternalStorageDirectory() + "/SecureLock");
        if (!folder.exists())
            //Directory Does Not Exist. I'll create it
            success = folder.mkdir();

        if (!success)
            //Failed : directory not created
            return;

        //save my image
        String path = Environment.getExternalStorageDirectory() + "/SecureLock/"+
                                (alarmStateSingleton.getPictureCounter()%3)+"_SecurityImage.jpg";

        final File file = new File(path);
        Log.i(TAG, "save: " + path);
        try (final OutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            this.picturesTaken.put(file.getPath(), bytes);
            alarmStateSingleton.incrementPictureCounter();
        } catch (IOException e) {
            Log.e(TAG, "Exception occurred while saving picture to external storage ", e);
        }
    }


    private void takeAnotherPicture() {
        this.currentCameraId = "0";
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if(alarmStateSingleton.getAlarm()){
                        openCamera();
                        loopActive = true;
                    }
                    else
                        loopActive = false;
                }

            }, 30* 1000);

    }

    private void closeCamera() {
        Log.d(TAG, "closing camera " + cameraDevice.getId());
        if (null != cameraDevice && !cameraClosed) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }
}