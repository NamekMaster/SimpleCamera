package io.github.namekmaster.simplecamera;

import android.app.Activity;
import android.content.Context;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.io.File;
import java.io.IOException;
import java.util.List;

import io.github.namekmaster.simplecamera.widget.CameraPreview;

import static io.github.namekmaster.simplecamera.util.CameraHelper.MEDIA_TYPE_VIDEO;
import static io.github.namekmaster.simplecamera.util.CameraHelper.checkCameraHardware;
import static io.github.namekmaster.simplecamera.util.CameraHelper.getBackFacingCameraId;
import static io.github.namekmaster.simplecamera.util.CameraHelper.getDefaultCameraInstance;
import static io.github.namekmaster.simplecamera.util.CameraHelper.getOptimalVideoSize;
import static io.github.namekmaster.simplecamera.util.CameraHelper.getOutputMediaFile;
import static io.github.namekmaster.simplecamera.util.CameraHelper.roundOrientation;
import static io.github.namekmaster.simplecamera.util.CameraHelper.setCameraDisplayOrientation;

/**
 * Created by LIU on 2016/9/13.
 */
public class VideoCaptureFragment extends Fragment {
    private static final String TAG = "VideoCaptureActivity";
    public static final String EXTRA_QUICK_CAPTURE = "extra_auto_capture";
    public static final String EXTRA_OUTPUT_URI = "extra_output_uri";

    public static final long ENABLE_SHUTTER_BUTTON_DELAY = 500;
    public static final long START_RECORDING_DELAY = 500;

    public static final int UPDATE_RECORDING_TIME = 1;
    public static final int ENABLE_SHUTTER_BUTTON = 2;
    public static final int START_RECORDING = 3;

    private boolean mIsVideoCaptureIntent;
    private boolean mQuickCapture;
    private Uri mOutput;

    private Camera mCamera;
    private CameraPreview mCameraPreview;
    private boolean isRecording;
    private MediaRecorder mRecorder;
    private File mOutputFile;
    private ImageButton mCaptureButton;
    private int mOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private MyOrientationListener mOrientationListener;
    private long mRecordingStartTime;
    private TextView mRecordingTimeText;
    private Handler mHandler;
    private OnCaptureListener mOnCaptureListener;
    private Camera.Parameters mParameters;
    private Camera.Size mPreviewSize;
    private ToggleButton mFlashMode;
    private boolean mPaused;

    public interface OnCaptureListener {
        void onCaptureStart(Uri output);

        void onCaptureFinish(Uri output);
    }

    private class MyOrientationListener extends OrientationEventListener {

        public MyOrientationListener(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            if (orientation == ORIENTATION_UNKNOWN)
                return;
            mOrientation = roundOrientation(orientation, mOrientation);
        }
    }

    private class MyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UPDATE_RECORDING_TIME:
                    updateRecordingTime();
                    break;
                case ENABLE_SHUTTER_BUTTON:
                    mCaptureButton.setEnabled(true);
                    break;
                case START_RECORDING:
                    mCaptureButton.performClick();
                    break;
            }
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (activity instanceof OnCaptureListener)
            mOnCaptureListener = (OnCaptureListener) activity;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!checkCameraHardware(getActivity())) {
            throw new UnsupportedOperationException();
        }
        handleArguments(getArguments());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_video_capture, container, false);
        mCaptureButton = (ImageButton) view.findViewById(R.id.capture_button);
        mCaptureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureClick();
            }
        });
        mRecordingTimeText = (TextView) view.findViewById(R.id.video_recording_time_text);

        mOrientationListener = new MyOrientationListener(getActivity());
        mHandler = new MyHandler();
        mCameraPreview = (CameraPreview) view.findViewById(R.id.preview);
        mFlashMode = ((ToggleButton) view.findViewById(R.id.flashMode));

        mFlashMode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mParameters = mCamera.getParameters();
                mParameters.setFlashMode(isChecked ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
                mCamera.setParameters(mParameters);
            }
        });
        return view;
    }

    private void handleArguments(Bundle args) {
        if (args != null && !args.isEmpty()) {
            mIsVideoCaptureIntent = true;
            mQuickCapture = args.getBoolean(EXTRA_QUICK_CAPTURE, false);
            mOutput = args.getParcelable(EXTRA_OUTPUT_URI);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mOrientationListener.enable();
        mCamera = getDefaultCameraInstance();
        setCameraDisplayOrientation(getActivity(), getBackFacingCameraId(), mCamera);
        mCameraPreview.setCamera(mCamera);
        mCaptureButton.setEnabled(true);
        mParameters = mCamera.getParameters();
    }

    @Override
    public void onResume() {
        mPaused = false;
        super.onResume();
        if (mQuickCapture) {
            mHandler.sendEmptyMessageDelayed(START_RECORDING, START_RECORDING_DELAY);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        mCameraPreview.setCamera(null);
        releaseMediaRecorder();
        releaseCameraAndPreview();
        mOrientationListener.disable();
    }

    private void releaseCameraAndPreview() {
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
        }
    }

    private void captureClick() {
        if (mPaused) {
            return;
        }
        boolean stop = isRecording;
        if (stop)
            stopVideoRecording();
        else {
            startVideoRecording();
        }
        mCaptureButton.setEnabled(false);
        if (!(mIsVideoCaptureIntent && stop))
            mHandler.sendEmptyMessageDelayed(ENABLE_SHUTTER_BUTTON, ENABLE_SHUTTER_BUTTON_DELAY);
    }

    private void startVideoRecording() {
        // TODO 应该一进去就初始化， 把camerapreview去掉， callback单独拿出来， surfaceChanged时候setParameters
        mRecorder = new MediaRecorder();
        mCamera.unlock();
        mRecorder.setCamera(mCamera);
        mRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_480P);
        mPreviewSize = getOptimalVideoSize(mParameters.getSupportedVideoSizes(), mParameters.getSupportedPreviewSizes(), mCameraPreview.getWidth(), mCameraPreview.getHeight());

        profile.videoFrameWidth = mPreviewSize.width;
        profile.videoFrameHeight = mPreviewSize.height;
        mRecorder.setProfile(profile);

        if (mOutput != null) {
            mOutputFile = new File(mOutput.getPath());
        } else {
            mOutputFile = getOutputMediaFile(getActivity(), MEDIA_TYPE_VIDEO);
        }
        if (mOutputFile == null) {
            Log.e(TAG, "Error creating media file");
            releaseMediaRecorder();
            return;
        }
        mRecorder.setOutputFile(mOutputFile.getAbsolutePath());

        // See android.hardware.Camera.Parameters.setRotation for
        // documentation.
        // Note that mOrientation here is the device orientation, which is the opposite of
        // what activity.getWindowManager().getDefaultDisplay().getRotation() would return,
        // which is the orientation the graphics need to rotate in order to render correctly.
        int rotation = 0;
        if (mOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
            Camera.CameraInfo info =
                    new Camera.CameraInfo();
            Camera.getCameraInfo(getBackFacingCameraId(), info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                rotation = (info.orientation - mOrientation + 360) % 360;
            } else {  // back-facing camera
                rotation = (info.orientation + mOrientation) % 360;
            }
        }
        mRecorder.setOrientationHint(rotation);

        try {
            mRecorder.prepare();
        } catch (IllegalStateException e) {
            releaseMediaRecorder();
            Log.d(TAG, "IllegalStateException preparing MediaRecorder: " + e.getMessage());
            return;
        } catch (IOException e) {
            releaseMediaRecorder();
            Log.d(TAG, "IOException preparing MediaRecorder: " + e.getMessage());
            return;
        }
        if (mOnCaptureListener != null) {
            mOnCaptureListener.onCaptureStart(Uri.fromFile(mOutputFile));
        }
        mRecorder.start();
        showRecordingUI(true);
        isRecording = true;
    }

    private void stopVideoRecording() {
        try {
            mRecorder.stop();
        } catch (RuntimeException e) {
            Log.d(TAG, "RuntimeException: stop() is called immediately after start()");
            mOutputFile.delete();
        }
        releaseMediaRecorder();
        showRecordingUI(false);
        if (mIsVideoCaptureIntent) {
            doReturnCaller();
        }
        isRecording = false;
    }

    private void doReturnCaller() {
        if (mOnCaptureListener != null)
            mOnCaptureListener.onCaptureFinish(Uri.fromFile(mOutputFile));
    }

    private void showRecordingUI(boolean recording) {
        if (recording) {
            mRecordingStartTime = System.currentTimeMillis();
            mRecordingTimeText.setText("");
            mRecordingTimeText.setVisibility(View.VISIBLE);
            mCaptureButton.setImageResource(R.drawable.btn_shutter_video_recording);
            updateRecordingTime();
        } else {
            mCaptureButton.setImageResource(R.drawable.btn_shutter_video);
            mRecordingTimeText.setVisibility(View.GONE);
        }
    }

    private void updateRecordingTime() {
        long now = System.currentTimeMillis();
        long delta = now - mRecordingStartTime;

        int updateTimeDelay = 1000;
        long actualUpdateTimeDelay = updateTimeDelay - delta % updateTimeDelay;

        String text = millisecondsToTimeString(delta);
        mRecordingTimeText.setText(text);
        mHandler.sendEmptyMessageDelayed(UPDATE_RECORDING_TIME, actualUpdateTimeDelay);
    }

    private String millisecondsToTimeString(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long remainingSeconds = seconds - minutes * 60;
        StringBuilder timeStringBuilder = new StringBuilder();
        if (minutes < 10) {
            timeStringBuilder.append("0");
        }
        timeStringBuilder.append(minutes)
                .append(":");
        if (remainingSeconds < 10) {
            timeStringBuilder.append("0");
        }
        timeStringBuilder.append(remainingSeconds);
        return timeStringBuilder.toString();
    }

    public void releaseMediaRecorder() {
        if (mRecorder != null) {
            if (isRecording) {
                try {
                    mRecorder.stop();
                } catch (RuntimeException e) {
                }
            }
            mHandler.removeMessages(UPDATE_RECORDING_TIME);
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
            mCamera.lock();
        }
    }

    @Override
    public void onPause() {
        mPaused = true;
        super.onPause();
    }

}
