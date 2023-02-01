package de.lifecapture.phonexrapp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaFormat.MIMETYPE_VIDEO_AVC
import android.util.Log
import android.view.Surface

class Encoder(val width : Int, val height : Int, format : String = MIMETYPE_VIDEO_AVC) {
    val mediaFormat = MediaFormat.createVideoFormat(format, width, height)
    val codecName = MediaCodecList(MediaCodecList.ALL_CODECS).findEncoderForFormat(mediaFormat)
    val mediaCodec = MediaCodec.createByCodecName(codecName)
    var surface: Surface? = null

    fun start() :Surface {
        Log.d("CODEC", codecName)
//        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.forEach() {
//            if (it.isEncoder) {
//                var info = it.name
//                it.supportedTypes.forEach {
//                    info += " " + it
//                }
//                Log.d("CODEC", info)
//            }
//        }
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 24000)
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = mediaCodec.createInputSurface()
        mediaCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(p0: MediaCodec, p1: Int) {
                // TODO("Not yet implemented")
            }

            override fun onOutputBufferAvailable(
                mc: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                val buffer = mc.getOutputBuffer(index)
                Log.d("ENCODER", "Length " +  buffer?.capacity())
                mc.releaseOutputBuffer(index, false)
            }

            override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) {
                // TODO("Not yet implemented")
            }

            override fun onError(p0: MediaCodec, p1: MediaCodec.CodecException) {
                // TODO("Not yet implemented")
            }
        })
        mediaCodec.flush()
        mediaCodec.start()
        return surface!!
    }

    fun stop() {
        mediaCodec.stop()
        mediaCodec.release()
        surface?.release()
        surface = null
    }
}