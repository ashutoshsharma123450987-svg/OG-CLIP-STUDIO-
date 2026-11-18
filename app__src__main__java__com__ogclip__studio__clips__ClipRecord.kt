package com.ogclip.studio.clips

/** One saved clip in the library. [uri] is a MediaStore content:// URI. */
data class ClipRecord(
    val id: String,
    val uri: String,
    val name: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** LOCAL for now. Phase 4 adds QUEUED / UPLOADING / UPLOADED / FAILED. */
    val uploadStatus: String = "LOCAL"
)
