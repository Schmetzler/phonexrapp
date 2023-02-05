package de.lifecapture.phonexrapp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaFormat.MIMETYPE_VIDEO_AVC
import android.util.Log
import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer

class Encoder(val width : Int, val height : Int, val bitrate : Int, val mainActivity: VrActivity, format : String = MIMETYPE_VIDEO_AVC) {
    val TAG = "ENCODER"
    val mediaFormat = MediaFormat.createVideoFormat(format, width, height)
    val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(mediaFormat)
    val mediaCodec = MediaCodec.createByCodecName(codecName)
    var surface: Surface? = null

    fun start() {
        Log.d("CODEC", codecName)
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        var startTime = System.nanoTime()
        var configSent = false
        //surface = mediaCodec.createInputSurface()
        mediaCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, inBuffId: Int) {
                val inputBuffer = mc.getInputBuffer(inBuffId)
                synchronized(mainActivity.encoderLock) {
                    val time = (System.nanoTime() - startTime)/1000
                    if (mainActivity.curFrame == null) {
                        mc.queueInputBuffer(0,0,0,time,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        Log.d("ENCODER", "Done")
                    } else {
                        inputBuffer?.put(mainActivity.curFrame!!)
                        val size = mainActivity.curFrame!!.size
                        mc.queueInputBuffer(inBuffId, 0, size, time, 0)
                        Log.d("ENCODER", "Input " + size + " " + time)
                    }
                }
            }

            override fun onOutputBufferAvailable(
                mc: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                if(mainActivity.socket.isConnected) {
                    val outBuffer = mc.getOutputBuffer(index)
                    if (configSent && outBuffer != null) {
                        val buf = ByteBuffer.allocate(outBuffer.limit())
                        buf.put(outBuffer)
                        buf.flip()
                        GlobalScope.launch(Dispatchers.IO) {
                            val ostream = DataOutputStream(mainActivity.socket.outputStream)
                            ostream.write(buf.array(), 0, outBuffer.limit())
                            ostream.flush()
                            Log.d(TAG, "Wrote " + outBuffer.limit().toString() + " bytes.")
                        }
                    }
                }
                mc.releaseOutputBuffer(index, false)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.d(
                    TAG, ("Updated output format! New height:"
                            + format.getInteger(MediaFormat.KEY_HEIGHT)) + " new width: " +
                            format.getInteger(MediaFormat.KEY_WIDTH)
                )
                if (mainActivity.socket.isConnected) {
                    val sps = format.getByteBuffer("csd-0")
                    val pps = format.getByteBuffer("csd-1")
                    val ostream = DataOutputStream(mainActivity.socket.outputStream)
                    if (sps != null && pps != null) {
                        GlobalScope.launch(Dispatchers.IO) {
                            ostream.write(sps.array(), 0, sps.limit())
                            ostream.write(pps.array(), 0, pps.limit())
                            ostream.flush()
                            configSent = true
                            Log.d(TAG, "Wrote " + sps.limit() + pps.limit() + " config bytes.")
                        }
                    } else {
                        Log.d(TAG, "No csd-0 and/or csd-1, Cannot send encoded data")
                    }
                }
            }

            override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) {
                // TODO("Not yet implemented")
            }
        })
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()
        // return surface!!
    }

    fun stop() {
        mediaCodec.stop()
        mediaCodec.release()
        //surface?.release()
        //surface = null
    }
}