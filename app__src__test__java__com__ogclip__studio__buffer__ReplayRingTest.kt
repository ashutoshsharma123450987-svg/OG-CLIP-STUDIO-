package com.ogclip.studio.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayRingTest {
    private val fps = 30
    private val gop = 60 // key frame every 2 s
    private val step = 1_000_000L / fps

    /** Feeds [seconds] of 30 fps video (1000 bytes per frame) and ~47 audio frames/s, in time order. */
    private fun feed(ring: ReplayRing, seconds: Int, withAudio: Boolean = false) {
        var audioPts = 0L
        for (i in 0 until seconds * fps) {
            val pts = i * step
            ring.addVideo(EncodedSample(ByteArray(1000), pts, i % gop == 0))
            if (withAudio) {
                while (audioPts <= pts) {
                    ring.addAudio(EncodedSample(ByteArray(300), audioPts, false))
                    audioPts += 21_333L
                }
            }
        }
    }

    @Test
    fun emptyRingGivesNoSnapshot() {
        assertNull(ReplayRing(30, Long.MAX_VALUE).snapshot(10_000_000L))
    }

    @Test
    fun windowEvictionKeepsAtLeastTheWindow() {
        val ring = ReplayRing(10, Long.MAX_VALUE)
        feed(ring, 60)
        val us = ring.bufferedUs()
        assertTrue("buffered $us should be >= 10 s", us >= 10_000_000L)
        assertTrue("buffered $us should be < 12 s", us < 12_000_000L)
    }

    @Test
    fun snapshotStartsOnKeyFrameAndCoversRequestedDuration() {
        val ring = ReplayRing(60, Long.MAX_VALUE)
        feed(ring, 30)
        val s = ring.snapshot(10_000_000L)
        assertNotNull(s)
        s!!
        assertTrue(s.video.first().isKey)
        assertTrue("duration ${s.durationUs}", s.durationUs >= 10_000_000L - step)
        assertTrue("duration ${s.durationUs}", s.durationUs < 12_000_000L)
        assertEquals((30 * fps - 1) * step, s.endPtsUs) // newest frame is the last one fed
    }

    @Test
    fun shortBufferReturnsEverything() {
        val ring = ReplayRing(60, Long.MAX_VALUE)
        feed(ring, 5)
        val s = ring.snapshot(60_000_000L)!!
        assertEquals(5 * fps, s.video.size)
        assertEquals(0L, s.startPtsUs)
    }

    @Test
    fun deltaFramesBeforeFirstKeyFrameAreIgnored() {
        val ring = ReplayRing(10, Long.MAX_VALUE)
        ring.addVideo(EncodedSample(ByteArray(100), 0, false))
        ring.addVideo(EncodedSample(ByteArray(100), step, false))
        assertEquals(0L, ring.bufferedBytes())
        ring.addVideo(EncodedSample(ByteArray(100), 2 * step, true))
        assertEquals(100L, ring.bufferedBytes())
        assertTrue(ring.snapshot(1_000_000L)!!.video.first().isKey)
    }

    @Test
    fun byteCapEvictsOldestGopsFirst() {
        val cap = 130_000L // one GOP is 60 * 1000 bytes
        val ring = ReplayRing(180, cap)
        feed(ring, 60)
        assertTrue("bytes ${ring.bufferedBytes()}", ring.bufferedBytes() <= cap)
        assertTrue(ring.snapshot(180_000_000L)!!.video.first().isKey)
    }

    @Test
    fun audioIsAlignedToVideoStart() {
        val ring = ReplayRing(10, Long.MAX_VALUE)
        feed(ring, 40, withAudio = true)
        val s = ring.snapshot(5_000_000L)!!
        assertTrue(s.audio.isNotEmpty())
        assertTrue(s.audio.all { it.ptsUs >= s.startPtsUs })
        // audio must also have been trimmed in the ring itself (no unbounded growth)
        val all = ring.snapshot(1_000_000_000L)!!
        assertTrue(all.audio.size < 15 * 48)
    }

    @Test
    fun audioBeforeAnyVideoIsDropped() {
        val ring = ReplayRing(10, Long.MAX_VALUE)
        ring.addAudio(EncodedSample(ByteArray(300), 0, false))
        assertEquals(0L, ring.bufferedBytes())
    }

    @Test
    fun clearResetsAndWaitsForNextKeyFrame() {
        val ring = ReplayRing(10, Long.MAX_VALUE)
        feed(ring, 5)
        ring.clear()
        assertEquals(0L, ring.bufferedBytes())
        assertNull(ring.snapshot(1_000_000L))
        ring.addVideo(EncodedSample(ByteArray(10), 10_000_000L, false))
        assertEquals(0L, ring.bufferedBytes())
        ring.addVideo(EncodedSample(ByteArray(10), 10_033_333L, true))
        assertEquals(10L, ring.bufferedBytes())
    }
}
