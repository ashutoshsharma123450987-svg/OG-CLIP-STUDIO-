package com.ogclip.studio.buffer

/** One encoded access unit (H.264 frame or AAC frame). Immutable once created. */
class EncodedSample(val data: ByteArray, val ptsUs: Long, val isKey: Boolean)

/** A consistent copy of the buffer contents, ready to be written to MP4. First video sample is a key frame. */
class ClipSnapshot(
    val video: List<EncodedSample>,
    val audio: List<EncodedSample>
) {
    val startPtsUs: Long get() = video.first().ptsUs
    val endPtsUs: Long get() = video.last().ptsUs
    val durationUs: Long get() = endPtsUs - startPtsUs
}

/**
 * Rolling buffer of already-encoded media, the heart of the replay system.
 *
 * Rules:
 *  - Video is kept in whole GOPs. The first video sample is always a key frame.
 *  - The oldest GOP is dropped only when the *next* key frame is still at least [windowUs] older
 *    than the newest frame, so a clip of [windowUs] can always be cut once the buffer is full.
 *  - A hard byte cap protects RAM. If it is hit, oldest GOPs go first even if that makes the
 *    buffered time shorter than the window (the UI shows the real buffered time).
 *  - Samples are never copied by [snapshot]; they are immutable, so muxing can run off-thread
 *    while the buffer keeps rolling.
 */
class ReplayRing(windowSeconds: Int, private val maxBytes: Long) {
    @Volatile private var windowUs: Long = windowSeconds * 1_000_000L

    private val video = ArrayDeque<EncodedSample>()
    private val audio = ArrayDeque<EncodedSample>()
    private var bytes = 0L
    private var waitingForKey = true
    private val lock = Any()

    fun setWindowSeconds(seconds: Int) {
        windowUs = seconds * 1_000_000L
    }

    fun addVideo(s: EncodedSample) = synchronized(lock) {
        if (waitingForKey) {
            if (!s.isKey) return@synchronized
            waitingForKey = false
        }
        video.addLast(s)
        bytes += s.data.size
        trim()
    }

    fun addAudio(s: EncodedSample) = synchronized(lock) {
        if (video.isEmpty()) return@synchronized // nothing to align with yet
        audio.addLast(s)
        bytes += s.data.size
    }

    /** Forget everything (used on pause). The next accepted video sample must be a key frame. */
    fun clear() = synchronized(lock) {
        video.clear()
        audio.clear()
        bytes = 0
        waitingForKey = true
    }

    /** True while the ring is empty and discarding delta frames until a key frame arrives. */
    fun isWaitingForKey(): Boolean = synchronized(lock) { waitingForKey }

    fun bufferedBytes(): Long = synchronized(lock) { bytes }

    fun bufferedUs(): Long = synchronized(lock) {
        if (video.isEmpty()) 0L else video.last().ptsUs - video.first().ptsUs
    }

    /**
     * Last [durationUs] of media, starting on the latest key frame at or before (newest - duration).
     * If the buffer is shorter than the request, everything buffered is returned.
     * Returns null when no video is buffered.
     */
    fun snapshot(durationUs: Long): ClipSnapshot? = synchronized(lock) {
        if (video.isEmpty()) return@synchronized null
        val all = ArrayList<EncodedSample>(video)
        val target = all[all.size - 1].ptsUs - durationUs
        var start = 0
        for (i in all.indices) {
            val s = all[i]
            if (s.isKey && s.ptsUs <= target) start = i
        }
        val v = ArrayList<EncodedSample>(all.subList(start, all.size))
        val from = v[0].ptsUs
        val a = ArrayList<EncodedSample>()
        for (s in audio) if (s.ptsUs >= from) a.add(s)
        ClipSnapshot(v, a)
    }

    // ---- internals (call with lock held) ----

    private fun trim() {
        // 1. time window: drop the first GOP while the second key frame is old enough
        while (true) {
            val k = secondKeyIndex()
            if (k < 0) break
            val newest = video.last().ptsUs
            if (video[k].ptsUs <= newest - windowUs) dropVideo(k) else break
        }
        // 2. memory cap
        while (bytes > maxBytes) {
            val k = secondKeyIndex()
            if (k < 0) break
            dropVideo(k)
        }
        // 3. audio older than the first video frame is useless
        val first = video.first().ptsUs
        while (audio.isNotEmpty() && audio.first().ptsUs < first) {
            bytes -= audio.removeFirst().data.size
        }
    }

    /** Index of the second key frame, or -1 if the buffer holds only one GOP. */
    private fun secondKeyIndex(): Int {
        for (i in 1 until video.size) if (video[i].isKey) return i
        return -1
    }

    private fun dropVideo(count: Int) {
        repeat(count) { bytes -= video.removeFirst().data.size }
    }
}
