package com.ogclip.studio.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun size(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
        else String.format(Locale.US, "%.1f MB", mb)
    }

    fun dateTime(ms: Long): String =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
}
