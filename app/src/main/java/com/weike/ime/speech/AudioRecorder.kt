package com.weike.ime.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(private val context: Context) {
    private val recording = AtomicBoolean(false)
    private var job: Job? = null
    private var recorder: AudioRecord? = null

    fun canRecord(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    fun start(
        scope: CoroutineScope,
        onChunk: (ByteArray, Int) -> Boolean,
        onLevel: (Float) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!canRecord()) {
            onError("请先在维刻输入法中授予麦克风权限")
            return
        }
        if (!recording.compareAndSet(false, true)) return
        val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minSize <= 0) {
            recording.set(false)
            onError("设备不支持所需的录音格式")
            return
        }
        try {
            val readSize = maxOf(minSize, RECOMMENDED_PACKET_SIZE)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                readSize * 2
            ).also { it.startRecording() }
            Log.d(TAG, "AudioRecord started; readBuffer=$readSize")
            job = scope.launch(Dispatchers.IO) {
                // The provider recommends 100-200 ms packets; 3200 bytes is 100 ms at 16 kHz PCM16 mono.
                val buffer = ByteArray(readSize)
                while (isActive && recording.get()) {
                    val count = recorder?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: break
                    if (count > 0) {
                        if (!onChunk(buffer, count)) {
                            recording.set(false)
                            onError("单次录音最长为 ${MAX_RECORDING_SECONDS} 秒")
                            break
                        }
                        onLevel(level(buffer, count))
                    } else {
                        Log.w(TAG, "AudioRecord read returned $count")
                    }
                }
            }
        } catch (error: SecurityException) {
            recording.set(false)
            onError("没有麦克风权限")
        } catch (error: IllegalStateException) {
            recording.set(false)
            onError("无法启动录音")
        }
    }

    fun stop() {
        recording.set(false)
        job?.cancel()
        job = null
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        Log.d(TAG, "AudioRecord stopped")
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_RECORDING_SECONDS = 90
        private const val RECOMMENDED_PACKET_SIZE = 3_200
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "WeikeAudio"

        private fun level(pcm: ByteArray, count: Int): Float {
            if (count < 2) return 0f
            var sum = 0.0
            var peak = 0
            var samples = 0
            var index = 0
            while (index + 1 < count) {
                val value = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xff)).toShort().toInt()
                sum += value.toDouble() * value
                peak = maxOf(peak, kotlin.math.abs(value))
                samples += 1
                index += 2
            }
            val rms = kotlin.math.sqrt(sum / samples) / Short.MAX_VALUE
            val peakLevel = peak.toDouble() / Short.MAX_VALUE
            return maxOf(rms, peakLevel * .55).toFloat().coerceIn(0f, 1f)
        }
    }
}
