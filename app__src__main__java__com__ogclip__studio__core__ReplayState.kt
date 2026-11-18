package com.ogclip.studio.core

/** Live status shared between the service (writer) and the UI (reader, polled once a second). */
object ReplayState {
    enum class Status { STOPPED, STARTING, RUNNING, PAUSED }

    @Volatile var status = Status.STOPPED
    @Volatile var startedAtMs = 0L
    @Volatile var windowSeconds = 0
    @Volatile var bufferedMs = 0L
    @Volatile var bufferedBytes = 0L
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var fps = 0
    @Volatile var kbps = 0
    @Volatile var audioOn = false
    @Volatile var audioNote = ""
    /** Last error or info message for the dashboard. */
    @Volatile var message = ""
    @Volatile var clipsThisSession = 0

    fun isActive() = status == Status.STARTING || status == Status.RUNNING || status == Status.PAUSED

    fun reset() {
        status = Status.STOPPED
        bufferedMs = 0
        bufferedBytes = 0
        audioOn = false
        audioNote = ""
    }
}
