package com.weike.ime.speech

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedPcmBufferTest {
    @Test
    fun `retains bytes within configured limit`() {
        val buffer = BoundedPcmBuffer(4)
        assertTrue(buffer.append(byteArrayOf(1, 2), 2))
        assertTrue(buffer.append(byteArrayOf(3, 4), 2))

        val snapshot = buffer.snapshotAndClear()

        assertFalse(snapshot.truncated)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), snapshot.pcm)
    }

    @Test
    fun `marks buffer truncated beyond configured limit`() {
        val buffer = BoundedPcmBuffer(2)
        assertTrue(buffer.append(byteArrayOf(1, 2), 2))
        assertFalse(buffer.append(byteArrayOf(3), 1))

        val snapshot = buffer.snapshotAndClear()

        assertTrue(snapshot.truncated)
        assertContentEquals(byteArrayOf(1, 2), snapshot.pcm)
    }
}
