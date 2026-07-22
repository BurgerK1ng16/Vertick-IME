package com.weike.ime.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.weike.ime.R

/** Service-scoped low-latency key sound. New taps replace the prior tail. */
class KeyboardSoundPlayer(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val soundId = soundPool.load(context, R.raw.vertick_keyboard_sound_typing, 1)

    @Volatile private var loaded = false
    private var activeStreamId = 0

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            loaded = status == 0 && sampleId == soundId
        }
    }

    fun play(volume: Float) {
        val resolved = volume.coerceIn(0f, 1f)
        if (!loaded || resolved <= 0f) return
        if (activeStreamId != 0) soundPool.stop(activeStreamId)
        activeStreamId = soundPool.play(soundId, resolved, resolved, 1, 0, 1f)
    }

    fun release() {
        activeStreamId = 0
        soundPool.release()
    }
}
