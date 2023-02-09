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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.*
import android.hardware.Camera.PreviewCallback
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
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
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
class VrActivity : AppCompatActivity(), PopupMenu.OnMenuItemClickListener, PreviewCallback, SensorEventListener {
    // Opaque native pointer to the native CardboardApp instance.
    // This object is owned by the VrActivity instance and passed to the native methods.

    private var nativeApp: Long = 0
    private var glView: GLSurfaceView? = null
    private var mCamera: Camera? = null
    private var mEncoder: Encoder? = null
    private var curFrame: ByteArray? = null
    private var mPreviewSize: Size? = null
    private var tcpVideoSocket = Socket()
    private var tcpSensorSocket = Socket()
    private var udpSocket = DatagramSocket()
    private var ipaddr: InetAddress? = null
    private var isStreaming = false
    private var passthrough = false    // If in passthrough mode or not (for setting recording hint)
    private var accelData = floatArrayOf(0.0f, 0.0f, 0.0f)
    private var magnetData = floatArrayOf(0.0f, 0.0f, 0.0f)
    private var gyroData = floatArrayOf(0.0f, 0.0f, 0.0f)
    private var sensorManager: SensorManager? = null

    // Changeable values (in settings)
    // Preview Size
    var previewWidth = 1280
    var previewHeight = 960
    // Address to stream video and sensorData
    var ip = "localhost"
    var videoPort = 50000
    var sensorPort = 50001
    // Whether it should be streamed via UDP will be set to false if IP is localhost
    var streamUdp = true
    // Force encoding, usually encoding is not needed for Fast USB connection (this may change in the future)
    var forceEncoding = false
    // the size of the passthrough relative to vr screen
    var passthroughSize = 0.5f
    // the Render viewport size... to be able to move the cardboard distortion around
    // (useful for bigger devices with smaller headsets)
    var vpX = 0
    var vpY = 0
    var vpHeight = 0
    var vpWidth = 0
    // Whether the center guide is visible or not
    var centerGuideVisible = false
    // The camera to show
    var camNr = 1

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

    override fun onSensorChanged(event: SensorEvent?) {
        val sensor = event!!.sensor;
        val arr = when(sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelData
            Sensor.TYPE_MAGNETIC_FIELD -> magnetData
            Sensor.TYPE_GYROSCOPE -> gyroData
            else -> null
        }
        if (arr != null) {
            arr[0] = event.values[0]
            arr[1] = event.values[1]
            arr[2] = event.values[2]
            if (sensor.type == Sensor.TYPE_ACCELEROMETER) {
                sendSensor()
            }
        }
    }

    private fun sendSensor() {
        val buffer = ByteBuffer.allocate(16*4+3*3*4)
        val headpose = nativeGetHeadPose(nativeApp)
        for(f in headpose!!){
            buffer.putFloat(f)
        }
        for(f in accelData) {
            buffer.putFloat(f)
        }
        for(f in gyroData) {
            buffer.putFloat(f)
        }
        for(f in magnetData) {
            buffer.putFloat(f)
        }
        buffer.rewind()
        if(isStreaming) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (ipaddr == null) {
                    ipaddr = InetAddress.getByName(ip)
                    if (ipaddr == InetAddress.getByName("localhost")) {
                        streamUdp = false
                    }
                }
                if (streamUdp) {
                    val packet = DatagramPacket(buffer.array(), 0, buffer.limit(), ipaddr, sensorPort)
                    udpSocket.send(packet)
                } else {
                    if(tcpSensorSocket.isConnected) {
                        val ostream = tcpSensorSocket.getOutputStream()
                        ostream.write(buffer.array(), 0, buffer.limit())
                        ostream.flush()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        nativeOnPause(nativeApp)
        glView!!.onPause()
        releaseCamera()
    }

    override fun onResume() {
        super.onResume()

        if(sensorManager == null) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }
        sensorManager?.registerListener(this, sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME)
        sensorManager?.registerListener(this, sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_GAME)
        sensorManager?.registerListener(this, sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)


        // TODO maybe adjust position to RenderviewPort
        val guide = findViewById<RelativeLayout>(R.id.ui_alignment_marker)
        if(centerGuideVisible) {
            guide.alpha = 1.0f
        } else {
            guide.alpha = 0.0f
        }

        // On Android P and below, checks for activity to READ_EXTERNAL_STORAGE. When it is not granted,
        // the application will request them. For Android Q and above, READ_EXTERNAL_STORAGE is optional
        // and scoped storage will be used instead. If it is provided (but not checked) and there are
        // device parameters saved in external storage those will be migrated to scoped storage.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !isReadExternalStorageEnabled) {
            requestPermissions()
            return
        }
        if (!isCameraEnabled) {
            requestCameraPermission()
            return
        }

        if (mCamera == null) {
            openCamera(previewWidth, previewHeight)
        }

        recreateEncoder()

        glView!!.onResume()
        nativeOnResume(nativeApp)
        nativeSetPassthroughSize(nativeApp, passthroughSize)



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
            startPreview()
        }

        override fun onSurfaceChanged(gl10: GL10, width: Int, height: Int) {
            vpWidth = width
            vpHeight = height
            nativeSetScreenParams(nativeApp, width, height)
        }

        override fun onDrawFrame(gl10: GL10) {
            mCameraTexture!!.updateTexImage()
            nativeSetRenderViewport(nativeApp, vpX, vpY, vpWidth, vpHeight)
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

    private fun recreateEncoder() {
        releaseEncoder()
        mEncoder = Encoder(
            mPreviewSize!!.width,
            mPreviewSize!!.height,
            8000000, "video/avc"
        )
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private fun releaseCamera() {
        releaseEncoder()
        if (mCamera != null) {
            mCamera!!.setPreviewCallback(null)
            mCamera!!.stopPreview()
            mCamera!!.release()
            mCamera = null
            Log.d(TAG, "releaseCamera -- done")
        }
        restartSocket()
    }

    private fun releaseEncoder() {
        if (mEncoder != null) {
            mEncoder!!.stop()
            mEncoder = null
        }
    }

    private fun restartSocket() {
        if (!tcpVideoSocket.isClosed) {
            tcpVideoSocket.close()
        }
        tcpVideoSocket = Socket()
        if (!tcpSensorSocket.isClosed) {
            tcpSensorSocket.close()
        }
        tcpSensorSocket = Socket()
        if (!udpSocket.isClosed) { // UDP for wifi
            udpSocket.close()
        }
        udpSocket = DatagramSocket()
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
        mCamera = Camera.open(camNr)
        /*
        for (i in 0 until numCameras) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                mCamera = Camera.open(i)
                break
            }
        }*/
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
        curFrame = ByteArray(mPreviewSize!!.width * mPreviewSize!!.height * 3)
        for (choice in choices) {
            Log.d("PrevSize", choice.toString())
        }

        // Try to set the frame rate to a constant value.
        //int mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(passthrough)
        // TODO allow change in settings
        // true = good for passthrough (as it is more fluid), bad for handtracking (as it has reduced size)
        // false = good for handtracking bad for passthrough (you get sick)
        mCamera!!.parameters = parms
        val previewFacts = mPreviewSize!!.width.toString() + "x" + mPreviewSize!!.height
        Log.i(TAG, "Camera config: $previewFacts")
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
        val item = popup.menu.findItem(R.id.start_stream)
        if(isStreaming) {
            item.title = getString(R.string.stop_stream)
        } else {
            item.title = getString(R.string.start_stream)
        }
        popup.setOnMenuItemClickListener(this)
        popup.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.switch_viewer) {
            releaseCamera()
            nativeSwitchViewer(nativeApp)
            return true
        }
        if (item.itemId == R.id.start_stream) {
            startStream(item)
            return true
        }
        return false
    }

    fun startStream(item: MenuItem) {
        if (!isStreaming) {
            runBlocking {
                launch(Dispatchers.IO) {
                    ipaddr = InetAddress.getByName(ip)
                    if(ipaddr == InetAddress.getByName("localhost")) {
                        streamUdp = false
                    }
                }
            }

            if(!streamUdp) {
                // wait for connection
                runBlocking {
                    launch(Dispatchers.IO) {
                        // TODO set some timeout and try again
                        tcpVideoSocket.connect(InetSocketAddress(ipaddr, videoPort), 0)
                        Log.d(TAG, "Video connected to $ipaddr:$videoPort")
                        tcpSensorSocket.connect(InetSocketAddress(ipaddr, sensorPort), 0)
                        Log.d(TAG, "Sensors connected to $ipaddr:$sensorPort")
                    }
                }
            }
            // Set preview callback
            mCamera?.setPreviewCallback(this)
            isStreaming = true
        } else {
            mCamera?.setPreviewCallback(null)
            recreateEncoder()
            restartSocket()
            isStreaming = false
        }
    }

    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        var size = 0
        val buffer = ByteBuffer.allocate(data!!.size + HEADER.size + 11)
        if(forceEncoding && mEncoder != null) {
            if(!mEncoder!!.isStarted) {
                mEncoder!!.start()
            }
            size = mEncoder!!.encodeData(data!!, curFrame!!)
            //Log.d(TAG, "Encoded to $size bytes")
        } else {
            buffer.put(HEADER)
            buffer.put(1)
            buffer.putShort(mPreviewSize!!.width.toShort())
            buffer.putShort(mPreviewSize!!.height.toShort())
            buffer.putShort(mPreviewSize!!.width.toShort())
            buffer.putInt(data.size)
            buffer.put(data)
            buffer.rewind()
        }

        runBlocking { launch(Dispatchers.IO) {
            if(tcpVideoSocket.isConnected && !forceEncoding) {
                val ostream = tcpVideoSocket.getOutputStream()
                ostream.write(buffer.array(), 0, buffer.limit())
                ostream.flush()
            } else {
                if (size > 0) {
                    if(forceEncoding && tcpVideoSocket.isConnected) {
                        val ostream = tcpVideoSocket.getOutputStream()
                        ostream.write(curFrame, 0, size)
                        ostream.flush()
                    } else {
                        val packet = DatagramPacket(curFrame, 0, size, ipaddr!!, videoPort)
                        udpSocket.send(packet)
                    }
                }
            }}
        }
    }

    /**
     * Checks for READ_EXTERNAL_STORAGE permission.
     *
     * @return whether the READ_EXTERNAL_STORAGE is already granted.
     */
    private val isReadExternalStorageEnabled: Boolean
        get() = (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED)

    private val isCameraEnabled: Boolean
        get() = (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)

    /** Handles the requests for activity permission to READ_EXTERNAL_STORAGE.  */
    private fun requestPermissions() {
        val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST_CODE)
    }

    private fun requestCameraPermission() {
        val permissions = arrayOf(Manifest.permission.CAMERA)
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CAMERA_CODE)
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
        if (requestCode == PERMISSIONS_REQUEST_CODE && !isReadExternalStorageEnabled) {
            Toast.makeText(this, "Storage is needed to store headset parameters", Toast.LENGTH_LONG).show()
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
        if (requestCode == PERMISSION_REQUEST_CAMERA_CODE && !isCameraEnabled) {
            Toast.makeText(this, "Camera is the source for Controller/Handtracking and Passthrough", Toast.LENGTH_LONG).show()
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.CAMERA
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
    private external fun nativeSetRenderViewport(nativeApp: Long, x: Int, y: Int, width: Int, height: Int)

    companion object {
        init {
            System.loadLibrary("cardboard_jni")
        }

        private val TAG = VrActivity::class.java.simpleName
        private val HEADER = "STARTFRAME".toByteArray()//byteArrayOf(0x00,0x00,0x00,0x00,0x02)

        // Permission request codes
        private const val PERMISSIONS_REQUEST_CODE = 2
        private const val PERMISSION_REQUEST_CAMERA_CODE = 1122
    }

}