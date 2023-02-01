// Code from https://hamzaasif-mobileml.medium.com/getting-frames-of-live-camera-footage-as-bitmaps-in-android-using-camera2-api-kotlin-40ba8d3afc76
package de.lifecapture.phonexrapp

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import android.graphics.Bitmap
import android.hardware.Camera
import android.media.Image
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.io.PrintWriter
import java.lang.Runnable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import kotlin.system.measureTimeMillis

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
class CameraView : AppCompatActivity(), ImageReader.OnImageAvailableListener, Camera.PreviewCallback  {

    private lateinit var fullscreenContent: FrameLayout
    private lateinit var fullscreenContentControls: LinearLayout
    private val hideHandler = Handler(Looper.myLooper()!!)

    private var socket = Socket()
    private lateinit var socketOut : DataOutputStream

    val hostname = "localhost"
    val port = 50000

    @SuppressLint("InlinedApi")
    private val hidePart2Runnable = Runnable {
        // Delayed removal of status and navigation bar
        if (Build.VERSION.SDK_INT >= 30) {
            fullscreenContent.windowInsetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            fullscreenContent.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LOW_PROFILE or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }
    private val showPart2Runnable = Runnable {
        // Delayed display of UI elements
        supportActionBar?.show()
        fullscreenContentControls.visibility = View.VISIBLE
    }
    private var isFullscreen: Boolean = false

    private val hideRunnable = Runnable { hide() }

    /**
     * Touch listener to use for in-layout UI controls to delay hiding the
     * system UI. This is to prevent the jarring behavior of controls going away
     * while interacting with activity UI.
     */
    private val delayHideTouchListener = View.OnTouchListener { view, motionEvent ->
        when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> if (AUTO_HIDE) {
                delayedHide(AUTO_HIDE_DELAY_MILLIS)
            }
            MotionEvent.ACTION_UP -> view.performClick()
            else -> {
            }
        }
        false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO CAMERA1 TESTCODE
        val nr = Camera.getNumberOfCameras()
        for( id in 0 until nr ){
            var info = Camera.CameraInfo()
            Camera.getCameraInfo(id, info)
            Log.d("CAMERA", "" + info.facing)
            val cam = Camera.open(id)
            val params = cam.parameters
            Log.d("CAMERA", "previewFormat " + params.previewFormat)
            params.supportedPreviewFormats.forEach() {
                Log.d("CAMERA", "prev_format " + it)
            }
            params.supportedPreviewSizes.forEach() {
                Log.d("CAMERA", "pre_format " + it.width + "x" + it.height)
            }
            params.supportedVideoSizes.forEach() {
                Log.d("CAMERA", "vid_format " + it.width + "x" + it.height)
            }
            params.supportedSceneModes.forEach() {
                Log.d("CAMERA", "Screen Modes " + it)
            }
            params.supportedPictureFormats.forEach() {
                Log.d("CAMERA", "picture formats " + it)
            }
            params.supportedPreviewFpsRange.forEach() {
                Log.d("CAMERA", "FPS " + it.toList())
            }
            cam.release()
        }




        //binding = ActivityCameraViewBinding.inflate(layoutInflater)
        setContentView(R.layout.activity_camera_view)//binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        isFullscreen = true

        // Set up the user interaction to manually show or hide the system UI.
        fullscreenContent = findViewById(R.id.fullscreenContent)
        fullscreenContent.setOnClickListener { toggle() }

        fullscreenContentControls = findViewById(R.id.fullscreenContentControls)

        // Upon interacting with UI controls, delay any scheduled hide()
        // operations to prevent the jarring behavior of controls going away
        // while interacting with the UI.
        findViewById<Button>(R.id.dummyButton).setOnTouchListener(delayHideTouchListener)

        runBlocking {
            launch(Dispatchers.IO) {
                socket.connect(InetSocketAddress(hostname, port))
                /*val ostream = DataOutputStream(socket.outputStream)
                ostream.writeBytes("Connected")
                ostream.flush()*/
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED
            ) {
                val permission = arrayOf(
                    Manifest.permission.CAMERA //JOHANNA
                )
                requestPermissions(permission, 1122)
            } else {
                setFragment()
            }
        }else{
            setFragment()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String?>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        //TODO show live camera footage
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setFragment()
        } else {
            finish()
        }
    }

    var previewHeight = 0;
    var previewWidth = 0
    var sensorOrientation = 0;
    //TODO fragment which show llive footage from camera

    protected fun setFragment() {
        val manager =
            getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var cameraId: String? = null
        try {
            cameraId = manager.cameraIdList[0]
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        val fragment: Fragment
        val camera2Fragment = CameraConnectionFragment.newInstance(
            object :
                CameraConnectionFragment.ConnectionCallback {
                override fun onPreviewSizeChosen(size: Size?, cameraRotation: Int) {
                    previewHeight = size!!.height
                    previewWidth = size.width
                    sensorOrientation = cameraRotation - getScreenOrientation()
                }
            },
            this,
            R.layout.camera_fragment,
            Size(2048, 1536)
        )
        camera2Fragment.setCamera(cameraId)
        fragment = camera2Fragment
        supportFragmentManager.beginTransaction().replace(R.id.fullscreenContent, fragment).commit()
    }

    //ON IMAGE AVAILABLE

    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var yRowStride = 0
    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null
    private var rgbFrameBitmap: Bitmap? = null

    fun int2bytearray(int: Int, size: Int) : ByteArray {
        val buffer = ByteArray(size)
        for (i in 0 until size) {
            buffer[i] = (int shr (i)*8).toByte()
        }
        return buffer
    }

    fun sendData(image: Image) {
        val format = byteArrayOf(16, 1)
        val width = image.planes[0].rowStride
        //val bWidth = int2bytearray(width, 2)
        val height = image.height
        //val bHeight = int2bytearray(height, 2)
        //val bWidth2 = int2bytearray(image.width, 2)

        var planeSize = 0
        val bY = image.planes[0].buffer
        val bUV = ByteBuffer.allocate((height shr 1) * width)
        if (image.planes[1].pixelStride == 2) {
            bUV.put(image.planes[1].buffer)
            for (row in 0 until (height shr 1)) {
                val index = row * width + image.width - 1
                bUV.put(index, image.planes[2].buffer[index-1])
            }
        }
        planeSize += bY.capacity()
        planeSize += bUV.capacity()
        bY.rewind()
        bUV.rewind()
        //val bPlaneSize = int2bytearray(planeSize, 4)
        val dummySensor = ByteArray(4*3*4)
        val buffer = ByteBuffer.allocateDirect(12+planeSize+4*4*3)
        buffer.put(format)
        buffer.putShort(width.toShort())
        buffer.putShort(height.toShort())
        buffer.putShort(image.width.toShort())
        //buffer.put(bWidth)
        //buffer.put(bHeight)
        //buffer.put(bWidth2)
        buffer.putInt(planeSize)
        buffer.put(bY)
        buffer.put(bUV)
        buffer.put(dummySensor)
        buffer.rewind()

        val ostream = DataOutputStream(socket.outputStream)
        ostream.write(buffer.array())
        ostream.flush()
    }

    var previewSending = false

    override fun onPreviewFrame(data : ByteArray, camera : Camera) {
        if (previewSending){
            return
        }

        val prevSize = camera.parameters.previewSize
        val format = byteArrayOf(16, 1)
        val dummySensor = ByteArray(4*4*3)
        val buffer = ByteBuffer.allocateDirect(12+data.size+4*4*3)
        buffer.put(format)
        buffer.putShort(prevSize.width.toShort())
        buffer.putShort(prevSize.height.toShort())
        buffer.putShort(prevSize.width.toShort())
        buffer.putInt(data.size)
        buffer.put(data)
        buffer.put(dummySensor)
        buffer.rewind()

        //runBlocking {
            lifecycleScope.launch(Dispatchers.IO) {
                previewSending = true
                val ostream = DataOutputStream(socket.outputStream)
                ostream.write(buffer.array())
                ostream.flush()
                previewSending = false
            }
        //}
    }

    private var frameskip = 0

    override fun onImageAvailable(reader: ImageReader) {
        // We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }

        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }

        try {
            val image = reader.acquireLatestImage() ?: return

            if (isProcessingFrame) {
                frameskip++
                image.close()
                return
            }
            if (frameskip > 0) {
                Log.d("CAMERA_FRAGMENT", "frameskip " + frameskip)
                frameskip = 0
            }
            isProcessingFrame = true

            runBlocking {
                launch(Dispatchers.IO) {
                    sendData(image)
                }
            }
            isProcessingFrame = false
            image.close()

/*            val planes = image.planes
            fillBytes(planes, yuvBytes)
            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            imageConverter = Runnable {
                ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes[0]!!,
                    yuvBytes[1]!!,
                    yuvBytes[2]!!,
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgbBytes!!
                )
            }
            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }
            processImage()*/
        } catch (e: Exception) {
            return
        }
    }

    private fun processImage() {
        imageConverter!!.run()
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        rgbFrameBitmap?.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)
        postInferenceCallback!!.run()
    }

    protected fun fillBytes(
        planes: Array<Image.Plane>,
        yuvBytes: Array<ByteArray?>
    ) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer[yuvBytes[i]!!]
        }
    }

    // END ON IMAGE AVAILABLE

    protected fun getScreenOrientation(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation
        } else {
            windowManager.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100)
    }

    private fun toggle() {
        if (isFullscreen) {
            hide()
        } else {
            show()
        }
    }

    private fun hide() {
        // Hide UI first
        supportActionBar?.hide()
        fullscreenContentControls.visibility = View.GONE
        isFullscreen = false

        // Schedule a runnable to remove the status and navigation bar after a delay
        hideHandler.removeCallbacks(showPart2Runnable)
        hideHandler.postDelayed(hidePart2Runnable, UI_ANIMATION_DELAY.toLong())
    }

    private fun show() {
        // Show the system bar
        if (Build.VERSION.SDK_INT >= 30) {
            fullscreenContent.windowInsetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            fullscreenContent.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        isFullscreen = true

        // Schedule a runnable to display UI elements after a delay
        hideHandler.removeCallbacks(hidePart2Runnable)
        hideHandler.postDelayed(showPart2Runnable, UI_ANIMATION_DELAY.toLong())
    }

    /**
     * Schedules a call to hide() in [delayMillis], canceling any
     * previously scheduled calls.
     */
    private fun delayedHide(delayMillis: Int) {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, delayMillis.toLong())
    }

    companion object {
        /**
         * Whether or not the system UI should be auto-hidden after
         * [AUTO_HIDE_DELAY_MILLIS] milliseconds.
         */
        private const val AUTO_HIDE = true

        /**
         * If [AUTO_HIDE] is set, the number of milliseconds to wait after
         * user interaction before hiding the system UI.
         */
        private const val AUTO_HIDE_DELAY_MILLIS = 3000

        /**
         * Some older devices needs a small delay between UI widget updates
         * and a change of the status and navigation bar.
         */
        private const val UI_ANIMATION_DELAY = 300
    }
}