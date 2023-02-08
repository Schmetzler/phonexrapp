package de.lifecapture.phonexrapp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaFormat.MIMETYPE_VIDEO_AVC
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.Socket
import java.nio.ByteBuffer

class Encoder(val width : Int, val height : Int, val bitrate : Int, format : String = MIMETYPE_VIDEO_AVC) {
    private val TAG = "ENCODER"
    private val mediaFormat = MediaFormat.createVideoFormat(format, width, height)
    private val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(mediaFormat)
    private val mediaCodec = MediaCodec.createByCodecName(codecName)
    private val timeoutUs: Long  = 50000
    private var mInfo: ByteArray? = null
    private val startTime = System.nanoTime()
    var isStarted = false

    fun start() {
        Log.d("CODEC", codecName)
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        if (Build.VERSION.SDK_INT >= 26) {
            mediaFormat.setInteger(MediaFormat.KEY_LATENCY,0)
        }

        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()
        isStarted = true
    }

    // Synchronous mode for MediaEncoder
    fun encodeData(input :ByteArray, output: ByteArray): Int {
        // INPUT
        val inBufferId = mediaCodec.dequeueInputBuffer(timeoutUs)
        val time = (System.nanoTime() - startTime) / 1000
        if (inBufferId >= 0) {
            val inputBuffer = mediaCodec.getInputBuffer(inBufferId)
            inputBuffer!!.put(input)
            val size = input.size
            mediaCodec.queueInputBuffer(inBufferId, 0, size, time, 0)
        }

        // OUTPUT
        var pos = 0
        val info = MediaCodec.BufferInfo()
        var outBufferId = mediaCodec.dequeueOutputBuffer(info,0)
        // Combine all output buffers to one datagram packet
        while(outBufferId >= 0) {
            val outBuffer = mediaCodec.getOutputBuffer(outBufferId)
            val outData = ByteArray(info.size)
            outBuffer!!.get(outData)
            if(mInfo == null) {
                // Store SPS/PPS buffer, to attach to KeyFrames
                val spsPpsBuffer = ByteBuffer.wrap(outData)
                if(spsPpsBuffer.int == 0x00000001) {
                    mInfo = ByteArray(outData.size)
                    System.arraycopy(outData, 0, mInfo!!, 0 , outData.size)
                } else {
                    return -1
                }
            }

            // Copy data to output buffer
            System.arraycopy(outData, 0, output, pos, outData.size)
            pos += outData.size

            //if(output[4] == 0x65.toByte()) {
                // attach SPS/PPS Buffer to KeyFrame
            //    System.arraycopy(mInfo!!, 0, output, 0, mInfo!!.size)
            //    System.arraycopy(outData, 0, output, mInfo!!.size, outData.size)
            //}
            mediaCodec.releaseOutputBuffer(outBufferId, false)
            outBufferId = mediaCodec.dequeueOutputBuffer(info, 0)
        }
        return pos
    }

    fun stop() {
        isStarted = false
        mediaCodec.stop()
        mediaCodec.release()
    }


}