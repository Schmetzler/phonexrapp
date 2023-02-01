/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.lifecapture.phonexrapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.provider.Settings;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.android.grafika.CameraUtils;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;

import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A Google Cardboard VR NDK sample application.
 *
 * <p>This is the main Activity for the sample application. It initializes a GLSurfaceView to allow
 * rendering.
 */
// TODO(b/184737638): Remove decorator once the AndroidX migration is completed.
@SuppressWarnings("deprecation")
public class VrActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener {
    static {
        System.loadLibrary("cardboard_jni");
    }

    private static final String TAG = VrActivity.class.getSimpleName();

    // Permission request codes
    private static final int PERMISSIONS_REQUEST_CODE = 2;

    private final int VID_WIDTH = 1280;
    private final int VID_HEIGHT = 960;
    private final int FPS = 30;

    // Opaque native pointer to the native CardboardApp instance.
    // This object is owned by the VrActivity instance and passed to the native methods.
    private long nativeApp;

    private GLSurfaceView glView;

    private Camera mCamera = null;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate(Bundle savedInstance) {
        super.onCreate(savedInstance);

        nativeApp = nativeOnCreate();

        setContentView(R.layout.vr_activity_view);
        glView = findViewById(R.id.surface_view);
        glView.setEGLContextClientVersion(3);
        Renderer renderer = new Renderer();
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // TODO(b/139010241): Avoid that action and status bar are displayed when pressing settings
        // button.
        setImmersiveSticky();
        View decorView = getWindow().getDecorView();
        decorView.setOnSystemUiVisibilityChangeListener(
                (visibility) -> {
                    if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                        setImmersiveSticky();
                    }
                });

        // Forces screen to max brightness.
        WindowManager.LayoutParams layout = getWindow().getAttributes();
        layout.screenBrightness = 1.f;
        getWindow().setAttributes(layout);

        // Prevents screen from dimming/locking.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onPause() {
        super.onPause();
        nativeOnPause(nativeApp);
        glView.onPause();
        releaseCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // On Android P and below, checks for activity to READ_EXTERNAL_STORAGE. When it is not granted,
        // the application will request them. For Android Q and above, READ_EXTERNAL_STORAGE is optional
        // and scoped storage will be used instead. If it is provided (but not checked) and there are
        // device parameters saved in external storage those will be migrated to scoped storage.
        if (VERSION.SDK_INT < VERSION_CODES.Q && !isReadExternalStorageEnabled()) {
            requestPermissions();
            return;
        }

        glView.onResume();
        nativeOnResume(nativeApp);

        if (mCamera == null) {
            openCamera(VID_WIDTH, VID_HEIGHT, FPS);
        }


        //if (mEglCore != null) {
        //startPreview();
        //}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nativeOnDestroy(nativeApp);
        nativeApp = 0;
        releaseCamera();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setImmersiveSticky();
        }
    }

    private EglCore mEglCore = null;
    private WindowSurface mDisplaySurface = null;
    private FullFrameRect mFullFrameBlit = null;
    private int mTextureId = 0;
    private SurfaceTexture mCameraTexture = null;

    private class Renderer implements GLSurfaceView.Renderer {
        @Override
        public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
            mTextureId = nativeOnSurfaceCreated(nativeApp);
            mCameraTexture = new SurfaceTexture(mTextureId);
            startPreview();
        }

        @Override
        public void onSurfaceChanged(GL10 gl10, int width, int height) {
            nativeSetScreenParams(nativeApp, width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl10) {
            mCameraTexture.updateTexImage();
            nativeOnDrawFrame(nativeApp);
        }
    }

    private void startPreview() {
        if (mCamera != null) {
            Log.d(TAG, "starting camera preview");
            try {
                mCamera.setPreviewTexture(mCameraTexture);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            mCamera.startPreview();
        }

//        // TODO: adjust bit rate based on frame rate?
//        // TODO: adjust video width/height based on what we're getting from the camera preview?
//        //       (can we guarantee that camera preview size is compatible with AVC video encoder?)
//        try {
//            mCircEncoder = new CircularEncoder(VIDEO_WIDTH, VIDEO_HEIGHT, 6000000,
//                    mCameraPreviewThousandFps / 1000, 7, mHandler);
//        } catch (IOException ioe) {
//            throw new RuntimeException(ioe);
//        }
//        mEncoderSurface = new WindowSurface(mEglCore, mCircEncoder.getInputSurface(), true);
//
//        updateControls();
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets mCameraPreviewFps to the expected frame rate (which might actually be variable).
     */
    private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a back-facing camera.
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No back-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();

        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Try to set the frame rate to a constant value.
        int mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(false);

        mCamera.setParameters(parms);

        Camera.Size cameraPreviewSize = parms.getPreviewSize();
        String previewFacts = cameraPreviewSize.width + "x" + cameraPreviewSize.height +
                " @" + (mCameraPreviewThousandFps / 1000.0f) + "fps";
        Log.i(TAG, "Camera config: " + previewFacts);
    }

    /** Callback for when close button is pressed. */
    public void closeSample(View view) {
        releaseCamera();
        Log.d(TAG, "Leaving VR sample");
        finish();
    }

    /** Callback for when settings_menu button is pressed. */
    public void showSettings(View view) {
        PopupMenu popup = new PopupMenu(this, view);
        MenuInflater inflater = popup.getMenuInflater();
        inflater.inflate(R.menu.settings_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(this);
        popup.show();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.switch_viewer) {
            releaseCamera();
            nativeSwitchViewer(nativeApp);
            return true;
        }
        return false;
    }

    /**
     * Checks for READ_EXTERNAL_STORAGE permission.
     *
     * @return whether the READ_EXTERNAL_STORAGE is already granted.
     */
    private boolean isReadExternalStorageEnabled() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Handles the requests for activity permission to READ_EXTERNAL_STORAGE. */
    private void requestPermissions() {
        final String[] permissions = new String[] {Manifest.permission.READ_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST_CODE);
    }

    /**
     * Callback for the result from requesting permissions.
     *
     * <p>When READ_EXTERNAL_STORAGE permission is not granted, the settings view will be launched
     * with a toast explaining why it is required.
     */
    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!isReadExternalStorageEnabled()) {
            Toast.makeText(this, "read_storage_permission", Toast.LENGTH_LONG).show();
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                // Permission denied with checking "Do not ask again". Note that in Android R "Do not ask
                // again" is not available anymore.
                launchPermissionsSettings();
            }
            finish();
        }
    }

    private void launchPermissionsSettings() {
        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void setImmersiveSticky() {
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private native long nativeOnCreate();

    private native int nativeOnSurfaceCreated(long nativeApp);

    private native void nativeOnDestroy(long nativeApp);

    private native void nativeOnDrawFrame(long nativeApp);

    private native void nativeOnPause(long nativeApp);

    private native void nativeOnResume(long nativeApp);

    private native void nativeSetScreenParams(long nativeApp, int width, int height);

    private native void nativeSwitchViewer(long nativeApp);

    private native float[] nativeGetHeadPose(long nativeApp);
}
