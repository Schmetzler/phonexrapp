package com.android.grafika.puttogether;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.android.grafika.ScaledDrawable2d;
import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.Sprite2d;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread that handles all rendering and camera operations.
 */
public class RenderThread extends Thread implements
        SurfaceTexture.OnFrameAvailableListener {
    // Object must be created on render thread to get correct Looper, but is used from
    // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
    // constructed object.
    private volatile RenderThread.RenderHandler mHandler;
    private final static String TAG = RenderThread.class.getName();

    // Used to wait for the thread to start.
    private Object mStartLock = new Object();
    private boolean mReady = false;

    private Camera mCamera1;
    private CameraDevice mCamera2;
    private int mCameraPreviewWidth, mCameraPreviewHeight;

    private EglCore mEglCore;
    private WindowSurface mWindowSurface;
    private int mWindowSurfaceWidth;
    private int mWindowSurfaceHeight;

    // Receives the output from the camera preview.
    private SurfaceTexture mCameraTexture;

    // Orthographic projection matrix.
    private float[] mDisplayProjectionMatrix = new float[16];

    private Texture2dProgram mTexProgram;
    private final ScaledDrawable2d mRectDrawable =
            new ScaledDrawable2d(Drawable2d.Prefab.RECTANGLE);
    private final Sprite2d mRect = new Sprite2d(mRectDrawable);

    private int mZoomPercent = DEFAULT_ZOOM_PERCENT;
    private int mSizePercent = DEFAULT_SIZE_PERCENT;
    private int mRotatePercent = DEFAULT_ROTATE_PERCENT;
    private float mPosX, mPosY;

    private boolean mApi1;
    private Activity mActivity;

    private String mCameraNr;


    /**
     * Constructor.  Pass in the MainHandler, which allows us to send stuff back to the
     * Activity.
     */
    public RenderThread(boolean api1, Activity activity) {
        mApi1 = api1;
        mActivity = activity;

        mCameraNr = null;
    }

    public Map<String, Size[]> getAvailableCamerasAndSizes() {
        Map<String, Size[]> resultMap = new HashMap<>();
        if(mApi1){
            for(Integer id = 0; id < Camera.getNumberOfCameras(); ++id) {
                Camera camera = Camera.open(id);
                Camera.Parameters params = camera.getParameters();
                List<Camera.Size> prevSizes = params.getSupportedPreviewSizes();
                Size[] sizes = (Size[]) prevSizes.stream().map(
                        (Camera.Size s) -> new Size(s.width, s.height)
                ).toArray();
                resultMap.put(id.toString(), sizes);
                camera.release();
            }

        } else {
            CameraManager manager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
            try {
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                    resultMap.put(id, sizes);
                }
            } catch (CameraAccessException e) {}
        }
        return resultMap;
    }

    public void setCameraAndSize(String camera, Size size) {
        mCameraNr = camera;
        mCameraPreviewWidth = size.getWidth();
        mCameraPreviewHeight = size.getHeight();
    }

    /**
     * Thread entry point.
     */
    @Override
    public void run() {
        if (mCameraNr == null) {
            return;
            // TODO get some error message
        }

        Looper.prepare();

        // We need to create the Handler before reporting ready.
        mHandler = new RenderHandler(this);
        synchronized (mStartLock) {
            mReady = true;
            mStartLock.notify();    // signal waitUntilReady()
        }

        // Prepare EGL and open the camera before we start handling messages.
        mEglCore = new EglCore(null, 0);
        openCamera();

        Looper.loop();

        Log.d(TAG, "looper quit");
        releaseCamera();
        releaseGl();
        mEglCore.release();

        synchronized (mStartLock) {
            mReady = false;
        }
    }

    /**
     * Waits until the render thread is ready to receive messages.
     * <p>
     * Call from the UI thread.
     */
    public void waitUntilReady() {
        synchronized (mStartLock) {
            while (!mReady) {
                try {
                    mStartLock.wait();
                } catch (InterruptedException ie) { /* not expected */ }
            }
        }
    }

    /**
     * Shuts everything down.
     */
    private void shutdown() {
        Log.d(TAG, "shutdown");
        Looper.myLooper().quit();
    }

    /**
     * Returns the render thread's Handler.  This may be called from any thread.
     */
    public RenderHandler getHandler() {
        return mHandler;
    }

    /**
     * Handles the surface-created callback from SurfaceView.  Prepares GLES and the Surface.
     */
    private void surfaceAvailable(SurfaceHolder holder, boolean newSurface) {
        Surface surface = holder.getSurface();
        mWindowSurface = new WindowSurface(mEglCore, surface, false);
        mWindowSurface.makeCurrent();

        // Create and configure the SurfaceTexture, which will receive frames from the
        // camera.  We set the textured rect's program to render from it.
        mTexProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
        int textureId = mTexProgram.createTextureObject();
        mCameraTexture = new SurfaceTexture(textureId);
        mRect.setTexture(textureId);

        if (!newSurface) {
            // This Surface was established on a previous run, so no surfaceChanged()
            // message is forthcoming.  Finish the surface setup now.
            //
            // We could also just call this unconditionally, and perhaps do an unnecessary
            // bit of reallocating if a surface-changed message arrives.
            mWindowSurfaceWidth = mWindowSurface.getWidth();
            mWindowSurfaceHeight = mWindowSurface.getHeight();
            finishSurfaceSetup();
        }

        mCameraTexture.setOnFrameAvailableListener(this);
    }

    /**
     * Releases most of the GL resources we currently hold (anything allocated by
     * surfaceAvailable()).
     * <p>
     * Does not release EglCore.
     */
    private void releaseGl() {
        GlUtil.checkGlError("releaseGl start");

        if (mWindowSurface != null) {
            mWindowSurface.release();
            mWindowSurface = null;
        }
        if (mTexProgram != null) {
            mTexProgram.release();
            mTexProgram = null;
        }
        GlUtil.checkGlError("releaseGl done");

        mEglCore.makeNothingCurrent();
    }

    /**
     * Handles the surfaceChanged message.
     * <p>
     * We always receive surfaceChanged() after surfaceCreated(), but surfaceAvailable()
     * could also be called with a Surface created on a previous run.  So this may not
     * be called.
     */
    private void surfaceChanged(int width, int height) {
        Log.d(TAG, "RenderThread surfaceChanged " + width + "x" + height);

        mWindowSurfaceWidth = width;
        mWindowSurfaceHeight = height;
        finishSurfaceSetup();
    }

    /**
     * Handles the surfaceDestroyed message.
     */
    private void surfaceDestroyed() {
        // In practice this never appears to be called -- the activity is always paused
        // before the surface is destroyed.  In theory it could be called though.
        Log.d(TAG, "RenderThread surfaceDestroyed");
        releaseGl();
    }

    /**
     * Sets up anything that depends on the window size.
     * <p>
     * Open the camera (to set mCameraAspectRatio) before calling here.
     */
    private void finishSurfaceSetup() {
        int width = mWindowSurfaceWidth;
        int height = mWindowSurfaceHeight;
        Log.d(TAG, "finishSurfaceSetup size=" + width + "x" + height +
                " camera=" + mCameraPreviewWidth + "x" + mCameraPreviewHeight);

        // Use full window.
        GLES20.glViewport(0, 0, width, height);

        // Simple orthographic projection, with (0,0) in lower-left corner.
        Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);

        // Default position is center of screen.
        mPosX = width / 2.0f;
        mPosY = height / 2.0f;

        updateGeometry();

        // Ready to go, start the camera.
        Log.d(TAG, "starting camera preview");
        try {
            mCamera1.setPreviewTexture(mCameraTexture);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera1.startPreview();
    }

    /**
     * Updates the geometry of mRect, based on the size of the window and the current
     * values set by the UI.
     */
    private void updateGeometry() {
        int width = mWindowSurfaceWidth;
        int height = mWindowSurfaceHeight;

        int smallDim = Math.min(width, height);
        // Max scale is a bit larger than the screen, so we can show over-size.
        float scaled = smallDim * (mSizePercent / 100.0f) * 1.25f;
        float cameraAspect = (float) mCameraPreviewWidth / mCameraPreviewHeight;
        int newWidth = Math.round(scaled * cameraAspect);
        int newHeight = Math.round(scaled);

        float zoomFactor = 1.0f - (mZoomPercent / 100.0f);
        int rotAngle = Math.round(360 * (mRotatePercent / 100.0f));

        mRect.setScale(newWidth, newHeight);
        mRect.setPosition(mPosX, mPosY);
        mRect.setRotation(rotAngle);
        mRectDrawable.setScale(zoomFactor);
    }

    @Override   // SurfaceTexture.OnFrameAvailableListener; runs on arbitrary thread
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mHandler.sendFrameAvailable();
    }

    /**
     * Handles incoming frame of data from the camera.
     */
    private void frameAvailable() {
        mCameraTexture.updateTexImage();
        draw();
    }

    /**
     * Draws the scene and submits the buffer.
     */
    private void draw() {
        GlUtil.checkGlError("draw start");

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        mRect.draw(mTexProgram, mDisplayProjectionMatrix);
        mWindowSurface.swapBuffers();

        GlUtil.checkGlError("draw done");
    }

    private void setZoom(int percent) {
        mZoomPercent = percent;
        updateGeometry();
    }

    private void setSize(int percent) {
        mSizePercent = percent;
        updateGeometry();
    }

    private void setRotate(int percent) {
        mRotatePercent = percent;
        updateGeometry();
    }

    private void setPosition(int x, int y) {
        mPosX = x;
        mPosY = mWindowSurfaceHeight - y;   // GLES is upside-down
        updateGeometry();
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width
     * and height with a fixed frame rate.
     * <p>
     * Sets mCameraPreviewWidth / mCameraPreviewHeight.
     */
    // TODO do something about FPS
    private void openCamera() {
        if (mApi1) {
            if (mCamera1 != null) {
                throw new RuntimeException("camera already initialized");
            }
            mCamera1 = Camera.open(Integer.parseInt(mCameraNr));
            Camera.Parameters params = mCamera1.getParameters();
            params.setPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight);
            //params.setRecordingHint(true)
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            mCamera1.setParameters(params);
        } else {
            // TODO cameraAPI2
            // mCamera2 = CameraDevice
        }
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (mCamera1 != null) {
            mCamera1.stopPreview();
            mCamera1.release();
            mCamera1 = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    /**
     * Handler for RenderThread.  Used for messages sent from the UI thread to the render thread.
     * <p>
     * The object is created on the render thread, and the various "send" methods are called
     * from the UI thread.
     */
    private static class RenderHandler extends Handler {
        private static final int MSG_SURFACE_AVAILABLE = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_SURFACE_DESTROYED = 2;
        private static final int MSG_SHUTDOWN = 3;
        private static final int MSG_FRAME_AVAILABLE = 4;
        private static final int MSG_ZOOM_VALUE = 5;
        private static final int MSG_SIZE_VALUE = 6;
        private static final int MSG_ROTATE_VALUE = 7;
        private static final int MSG_POSITION = 8;
        private static final int MSG_REDRAW = 9;

        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
        // but no real harm in it.
        private WeakReference<RenderThread> mWeakRenderThread;

        /**
         * Call from render thread.
         */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /**
         * Sends the "surface available" message.  If the surface was newly created (i.e.
         * this is called from surfaceCreated()), set newSurface to true.  If this is
         * being called during Activity startup for a previously-existing surface, set
         * newSurface to false.
         * <p>
         * The flag tells the caller whether or not it can expect a surfaceChanged() to
         * arrive very soon.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceAvailable(SurfaceHolder holder, boolean newSurface) {
            sendMessage(obtainMessage(MSG_SURFACE_AVAILABLE,
                    newSurface ? 1 : 0, 0, holder));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format, int width,
                                       int height) {
            // ignore format
            sendMessage(obtainMessage(MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p>
         * Call from UI thread.
         */
        public void sendSurfaceDestroyed() {
            sendMessage(obtainMessage(MSG_SURFACE_DESTROYED));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p>
         * Call from UI thread.
         */
        public void sendShutdown() {
            sendMessage(obtainMessage(MSG_SHUTDOWN));
        }

        /**
         * Sends the "frame available" message.
         * <p>
         * Call from UI thread.
         */
        public void sendFrameAvailable() {
            sendMessage(obtainMessage(MSG_FRAME_AVAILABLE));
        }

        /**
         * Sends the "zoom value" message.  "progress" should be 0-100.
         * <p>
         * Call from UI thread.
         */
        public void sendZoomValue(int progress) {
            sendMessage(obtainMessage(MSG_ZOOM_VALUE, progress, 0));
        }

        /**
         * Sends the "size value" message.  "progress" should be 0-100.
         * <p>
         * Call from UI thread.
         */
        public void sendSizeValue(int progress) {
            sendMessage(obtainMessage(MSG_SIZE_VALUE, progress, 0));
        }

        /**
         * Sends the "rotate value" message.  "progress" should be 0-100.
         * <p>
         * Call from UI thread.
         */
        public void sendRotateValue(int progress) {
            sendMessage(obtainMessage(MSG_ROTATE_VALUE, progress, 0));
        }

        /**
         * Sends the "position" message.  Sets the position of the rect.
         * <p>
         * Call from UI thread.
         */
        public void sendPosition(int x, int y) {
            sendMessage(obtainMessage(MSG_POSITION, x, y));
        }

        /**
         * Sends the "redraw" message.  Forces an immediate redraw.
         * <p>
         * Call from UI thread.
         */
        public void sendRedraw() {
            sendMessage(obtainMessage(MSG_REDRAW));
        }

        @Override  // runs on RenderThread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);

            RenderThread renderThread = mWeakRenderThread.get();
            if (renderThread == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            switch (what) {
                case MSG_SURFACE_AVAILABLE:
                    renderThread.surfaceAvailable((SurfaceHolder) msg.obj, msg.arg1 != 0);
                    break;
                case MSG_SURFACE_CHANGED:
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_SURFACE_DESTROYED:
                    renderThread.surfaceDestroyed();
                    break;
                case MSG_SHUTDOWN:
                    renderThread.shutdown();
                    break;
                case MSG_FRAME_AVAILABLE:
                    renderThread.frameAvailable();
                    break;
                case MSG_ZOOM_VALUE:
                    renderThread.setZoom(msg.arg1);
                    break;
                case MSG_SIZE_VALUE:
                    renderThread.setSize(msg.arg1);
                    break;
                case MSG_ROTATE_VALUE:
                    renderThread.setRotate(msg.arg1);
                    break;
                case MSG_POSITION:
                    renderThread.setPosition(msg.arg1, msg.arg2);
                    break;
                case MSG_REDRAW:
                    renderThread.draw();
                    break;
                default:
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }
}