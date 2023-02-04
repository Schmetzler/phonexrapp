package de.lifecapture.phonexrapp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaFormat.MIMETYPE_VIDEO_AVC
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class Encoder(val width : Int, val height : Int, val bitrate : Int, val mainActivity: VrActivity, format : String = MIMETYPE_VIDEO_AVC) {
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
        var startTime = System.nanoTime();
        //surface = mediaCodec.createInputSurface()
        mediaCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, inBuffId: Int) {
                val inputBuffer = mc.getInputBuffer(inBuffId)
                synchronized(mainActivity.encoderLock) {
                    val time = (System.nanoTime() - startTime)/1000
                    if (mainActivity.cur_frame == null) {
                        mc.queueInputBuffer(0,0,0,time,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        Log.d("ENCODER", "Done")
                    } else {
                        inputBuffer?.put(mainActivity.cur_frame)
                        val size = mainActivity.cur_frame.size
                        mc.queueInputBuffer(inBuffId, 0, size, time, 0)
                        Log.d("ENCODER", "Input " + size)
                    }
                }
                // TODO("Not yet implemented")
            }

            override fun onOutputBufferAvailable(
                mc: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                val buffer = mc.getOutputBuffer(index)
                Log.d("ENCODER", "Output " +  info.size)
                mc.releaseOutputBuffer(index, false)
            }

            override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) {
                // TODO("Not yet implemented")
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