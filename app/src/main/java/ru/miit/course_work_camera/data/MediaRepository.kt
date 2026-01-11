package ru.miit.course_work_camera.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.miit.course_work_camera.model.MediaItem
import ru.miit.course_work_camera.model.MediaType
import java.io.File
import java.time.Instant

private const val IMAGES_FOLDER = "Pictures/CourseWorkCamera"
private const val VIDEOS_FOLDER = "Movies/CourseWorkCamera"

class MediaRepository(private val context: Context) {
    private val resolver = context.contentResolver

    suspend fun loadMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        val images = queryImages()
        val videos = queryVideos()
        (images + videos).sortedByDescending { it.createdAt }
    }

    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        resolver.delete(uri, null, null) > 0
    }

    fun createImageRequest(): ImageRequest {
        val displayName = "IMG_${System.currentTimeMillis()}.jpg"
        val outputOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, IMAGES_FOLDER)
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }
            ImageCapture.OutputFileOptions.Builder(
                resolver,
                imagesCollection(),
                values
            ).build()
        } else {
            val directory = legacyDirectory(Environment.DIRECTORY_PICTURES)
            val file = File(directory, displayName)
            ImageCapture.OutputFileOptions.Builder(file).build()
        }
        val fallbackUri = legacyUri(displayName, Environment.DIRECTORY_PICTURES)
        return ImageRequest(outputOptions, fallbackUri)
    }

    fun createVideoRequest(): MediaStoreOutputOptions {
        val displayName = "VID_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, VIDEOS_FOLDER)
            } else {
                val legacy = File(legacyDirectory(Environment.DIRECTORY_MOVIES), displayName)
                put(MediaStore.Video.Media.DATA, legacy.absolutePath)
            }
        }
        return MediaStoreOutputOptions.Builder(resolver, videosCollection())
            .setContentValues(values)
            .build()
    }

    private fun queryImages(): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection: String?
        val args: Array<String>?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("$IMAGES_FOLDER%")
        } else {
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            args = arrayOf("%CourseWorkCamera%")
        }

        val collection = imagesCollection()
        return resolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex) ?: ""
                    val dateSeconds = cursor.getLong(dateIndex)
                    val uri = ContentUris.withAppendedId(collection, id)
                    add(
                        MediaItem(
                            uri = uri,
                            type = MediaType.Photo,
                            displayName = name,
                            createdAt = Instant.ofEpochSecond(dateSeconds)
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun queryVideos(): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION
        )
        val selection: String?
        val args: Array<String>?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("$VIDEOS_FOLDER%")
        } else {
            selection = "${MediaStore.Video.Media.DATA} LIKE ?"
            args = arrayOf("%CourseWorkCamera%")
        }
        val collection = videosCollection()
        return resolver.query(
            collection,
            projection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex) ?: ""
                    val dateSeconds = cursor.getLong(dateIndex)
                    val duration = cursor.getLong(durationIndex)
                    val uri = ContentUris.withAppendedId(collection, id)
                    add(
                        MediaItem(
                            uri = uri,
                            type = MediaType.Video,
                            displayName = name,
                            createdAt = Instant.ofEpochSecond(dateSeconds),
                            durationMillis = duration
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun imagesCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun videosCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun legacyDirectory(type: String): File {
        val directory = Environment.getExternalStoragePublicDirectory(type)
        val scoped = File(directory, "CourseWorkCamera")
        if (!scoped.exists()) scoped.mkdirs()
        return scoped
    }

    private fun legacyUri(displayName: String, folder: String): Uri? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val file = File(legacyDirectory(folder), displayName)
            Uri.fromFile(file)
        } else null
}

data class ImageRequest(
    val outputOptions: ImageCapture.OutputFileOptions,
    val fallbackUri: Uri?
)
