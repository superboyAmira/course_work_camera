package ru.miit.course_work_camera.model

import android.net.Uri
import java.time.Instant

enum class MediaType {
    Photo, Video
}

data class MediaItem(
    val uri: Uri,
    val type: MediaType,
    val displayName: String,
    val createdAt: Instant,
    val durationMillis: Long? = null
)
