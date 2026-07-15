package com.weike.ime.speech

import java.io.ByteArrayOutputStream

/** Thread-safe, session-owned PCM buffer that cannot grow without bound. */
class BoundedPcmBuffer(private val maxBytes: Int) {
    data class Snapshot(val pcm: ByteArray, val truncated: Boolean)

    private val lock = Any()
    private var output = ByteArrayOutputStream()
    private var truncated = false

    fun append(bytes: ByteArray, count: Int): Boolean = synchronized(lock) {
        if (count <= 0 || truncated) return !truncated
        if (output.size() + count > maxBytes) {
            truncated = true
            return false
        }
        output.write(bytes, 0, count)
        true
    }

    fun snapshotAndClear(): Snapshot = synchronized(lock) {
        val snapshot = Snapshot(output.toByteArray(), truncated)
        output = ByteArrayOutputStream()
        truncated = false
        snapshot
    }

    fun clear() = synchronized(lock) {
        output.reset()
        truncated = false
    }
}
