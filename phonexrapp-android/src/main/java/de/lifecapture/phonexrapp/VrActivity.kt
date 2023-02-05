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
package de.lifecapture.phonexrapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import java.util.stream.Collectors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A Google Cardboard VR NDK sample application.
 *
 *
 * This is the main Activity for the sample application. It initializes a GLSurfaceView to allow
 * rendering.
 */
// TODO(b/184737638): Remove decorator once the AndroidX migration is completed.
// TODO Available Settings: Passthrough size, recording_hint (passthrough/normal), PreviewSize, Passthrough or Normal
class VrActivity : AppCompatActivity(), PopupMenu.OnMenuItemClickListener {
    private val VID_WIDTH = 1280
    private val VID_HEIGHT = 960
    private val IPADDR = "localhost"
    private val PORT = 50000

    // Opaque native pointer to the native CardboardApp instance.
    // This object is owned by the VrActivity instance and passed to the native methods.
    private var nativeApp: Long = 0
    private var glView: GLSurfaceView? = null
    private var mCamera: Camera? = null
    private var mEncoder: Encoder? = null
    var curFrame: ByteArray? = null
    private var mPreviewSize: Size? = null
    val encoderLock = Any()
    var socket = Socket()

    @SuppressLint("ClickableViewAccessibility")
    public override fun onCreate(savedInstance: Bundle?) {
        super.onCreate(savedInstance)
        nativeApp = nativeOnCreate()
        setContentView(R.layout.vr_activity_view)
        glView = findViewById(R.id.surface_view)
        glView!!.setEGLContextClientVersion(3)
        val renderer = Renderer()
        glView!!.setRenderer(renderer)
        glView!!.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // TODO(b/139010241): Avoid that action and status bar are displayed when pressing settings
        // button.
        setImmersiveSticky()
        val decorView = window.decorView
        decorView.setOnSystemUiVisibilityChangeListener { visibility: Int ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                setImmersiveSticky()
            }
        }

        // Forces screen to max brightness.
        val layout = window.attributes
        layout.screenBrightness = 1f
        window.attributes = layout

        // Prevents screen from dimming/locking.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        nativeOnPause(nativeApp)
        glView!!.onPause()
        releaseCamera()
    }

    override fun onResume() {
        super.onResume()

        // On Android P and below, checks for activity to READ_EXTERNAL_STORAGE. When it is not granted,
        // the application will request them. For Android Q and above, READ_EXTERNAL_STORAGE is optional
        // and scoped storage will be used instead. If it is provided (but not checked) and there are
        // device parameters saved in external storage those will be migrated to scoped storage.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !isReadExternalStorageEnabled) {
            requestPermissions()
            return
        }
        if (mCamera == null) {
            openCamera(VID_WIDTH, VID_HEIGHT)
        }
        if (mEncoder == null) {
            mEncoder = Encoder(
                mPreviewSize!!.width,
                mPreviewSize!!.height,
                8000000, this, "video/avc"
            )
        }
        if (!socket.isConnected) {
            runBlocking {
                launch(Dispatchers.IO)
                {
                    try {
                        socket.connect(InetSocketAddress(IPADDR, PORT))
                    } catch (e: IOException) {
                        Log.d(TAG, "Cannot connect to server")
                    }
                }
            }
        }

        glView!!.onResume()
        nativeOnResume(nativeApp)

        //if (mEglCore != null) {
        //startPreview();
        //}
    }

    override fun onDestroy() {
        super.onDestroy()
        nativeOnDestroy(nativeApp)
        nativeApp = 0
        releaseCamera()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setImmersiveSticky()
        }
    }

    private var mTextureId = 0
    private var mCameraTexture: SurfaceTexture? = null

    private inner class Renderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl10: GL10, eglConfig: EGLConfig) {
            mTextureId = nativeOnSurfaceCreated(nativeApp)
            mCameraTexture = SurfaceTexture(mTextureId)

            /*try {
                mEncoderCore = new VideoEncoderCore(VID_WIDTH, VID_HEIGHT, 20000);
            } catch (IOException e) {
                Log.d(TAG, "Dont encode");
            }

            EGL10 egl = (EGL10) EGLContext.getEGL();

            int[] attrib_list = {
                    EGL10.EGL_NONE
            };

            EGLDisplay display = egl.eglGetCurrentDisplay();
            mEncoderSurface = egl.eglCreateWindowSurface(
                    display, eglConfig, mEncoderCore.getInputSurface(), attrib_list
            );*/startPreview()
        }

        override fun onSurfaceChanged(gl10: GL10, width: Int, height: Int) {
            nativeSetScreenParams(nativeApp, width, height)
        }

        override fun onDrawFrame(gl10: GL10) {
            mCameraTexture!!.updateTexImage()
            nativeOnDrawFrame(nativeApp)
        }
    }

    private fun startPreview() {
        if (mCamera != null) {
            Log.d(TAG, "starting camera preview")
            try {
                mCamera!!.setPreviewTexture(mCameraTexture)
            } catch (ioe: IOException) {
                throw RuntimeException(ioe)
            }
            mCamera!!.startPreview()
        }
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private fun releaseCamera() {
        if (mEncoder != null) {
            mEncoder!!.stop()
            mEncoder = null
        }
        if (mCamera != null) {
            mCamera!!.setPreviewCallback(null)
            mCamera!!.stopPreview()
            mCamera!!.release()
            mCamera = null
            Log.d(TAG, "releaseCamera -- done")
        }
        if (!socket.isClosed) {
            socket.close()
            socket = Socket()
        }
    }

    /**
     * Given `choices` of `Size`s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the minimum of both, or an exact match if possible.
     *
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width The minimum desired width
     * @param height The minimum desired height
     * @return The optimal `Size`, or an arbitrary one if none were big enough
     */
    protected fun chooseOptimalSize(choices: List<Size>, width: Int, height: Int): Size {
        val minSize = Integer.max(
            Integer.min(width, height),
            320
        )
        val desiredSize = Size(width, height)

        // Collect the supported resolutions that are at least as big as the preview Surface
        var exactSizeFound = false
        val bigEnough: MutableList<Size> = ArrayList()
        for (option in choices) {
            if (option === desiredSize) {
                // Set the size but don't return yet so that remaining sizes will still be logged.
                exactSizeFound = true
            }
            if (option.height >= minSize && option.width >= minSize) {
                bigEnough.add(option)
            }
        }
        if (exactSizeFound) {
            return desiredSize
        }

        // Pick the smallest of those, assuming we found any
        return if (bigEnough.size > 0) {
            Collections.min(bigEnough, Comparator.comparingInt { lhs: Size -> lhs.width * lhs.height })
        } else {
            choices[0]
        }
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     *
     *
     * Sets mCameraPreviewFps to the expected frame rate (which might actually be variable).
     */
    private fun openCamera(desiredWidth: Int, desiredHeight: Int) {
        if (mCamera != null) {
            throw RuntimeException("camera already initialized")
        }
        val info = Camera.CameraInfo()

        // Try to find a back-facing camera.
        val numCameras = Camera.getNumberOfCameras()
        for (i in 0 until numCameras) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                mCamera = Camera.open(i)
                break
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No back-facing camera found; opening default")
            mCamera = Camera.open() // opens first back-facing camera
        }
        if (mCamera == null) {
            throw RuntimeException("Unable to open camera")
        }
        val parms = mCamera!!.parameters
        val choices_ = parms.supportedPreviewSizes
        val choices = choices_.stream().map { `in`: Camera.Size -> Size(`in`.width, `in`.height) }.collect(Collectors.toList())
        mPreviewSize = chooseOptimalSize(choices, desiredWidth, desiredHeight)
        parms.setPreviewSize(mPreviewSize!!.width, mPreviewSize!!.height)
        for (choice in choices) {
            Log.d("PrevSize", choice.toString())
        }

        // Try to set the frame rate to a constant value.
        //int mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true)
        // TODO allow change in settings
        // true = good for passthrough (as it is more fluid), bad for handtracking (as it has reduced size)
        // false = good for handtracking bad for passthrough (you get sick)
        mCamera!!.parameters = parms
        val previewFacts = mPreviewSize!!.width.toString() + "x" + mPreviewSize!!.height
        Log.i(TAG, "Camera config: $previewFacts")
        mCamera!!.setPreviewCallback { data: ByteArray?, camera: Camera? ->
            synchronized(encoderLock) {
                if (curFrame == null) {
                    mEncoder!!.start()
                }
                curFrame = data
            }
        }
    }

    /** Callback for when close button is pressed.  */
    fun closeSample(view: View?) {
        releaseCamera()
        Log.d(TAG, "Leaving VR sample")
        finish()
    }

    /** Callback for when settings_menu button is pressed.  */
    fun showSettings(view: View?) {
        val popup = PopupMenu(this, view)
        val inflater = popup.menuInflater
        inflater.inflate(R.menu.settings_menu, popup.menu)
        popup.setOnMenuItemClickListener(this)
        popup.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.switch_viewer) {
            releaseCamera()
            nativeSwitchViewer(nativeApp)
            return true
        }
        return false
    }

    /**
     * Checks for READ_EXTERNAL_STORAGE permission.
     *
     * @return whether the READ_EXTERNAL_STORAGE is already granted.
     */
    private val isReadExternalStorageEnabled: Boolean
        private get() = (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED)

    /** Handles the requests for activity permission to READ_EXTERNAL_STORAGE.  */
    private fun requestPermissions() {
        val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST_CODE)
    }

    /**
     * Callback for the result from requesting permissions.
     *
     *
     * When READ_EXTERNAL_STORAGE permission is not granted, the settings view will be launched
     * with a toast explaining why it is required.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!isReadExternalStorageEnabled) {
            Toast.makeText(this, "read_storage_permission", Toast.LENGTH_LONG).show()
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.READ_EXTERNAL_STORAGE
                )
            ) {
                // Permission denied with checking "Do not ask again". Note that in Android R "Do not ask
                // again" is not available anymore.
                launchPermissionsSettings()
            }
            finish()
        }
    }

    private fun launchPermissionsSettings() {
        val intent = Intent()
        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        intent.data = Uri.fromParts("package", packageName, null)
        startActivity(intent)
    }

    private fun setImmersiveSticky() {
        window
            .decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private external fun nativeOnCreate(): Long
    private external fun nativeOnSurfaceCreated(nativeApp: Long): Int
    private external fun nativeOnDestroy(nativeApp: Long)
    private external fun nativeOnDrawFrame(nativeApp: Long)
    private external fun nativeOnPause(nativeApp: Long)
    private external fun nativeOnResume(nativeApp: Long)
    private external fun nativeSetScreenParams(nativeApp: Long, width: Int, height: Int)
    private external fun nativeSwitchViewer(nativeApp: Long)
    private external fun nativeGetHeadPose(nativeApp: Long): FloatArray?
    private external fun nativeSetPassthroughSize(nativeApp: Long, passthrough_size: Float)

    companion object {
        init {
            System.loadLibrary("cardboard_jni")
        }

        private val TAG = VrActivity::class.java.simpleName

        // Permission request codes
        private const val PERMISSIONS_REQUEST_CODE = 2
    }
}