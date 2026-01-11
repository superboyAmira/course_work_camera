package ru.miit.course_work_camera.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object VideoMerger {
    private const val TAG = "VideoMerger"
    
    suspend fun mergeVideos(
        context: Context,
        videoUris: List<Uri>,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (videoUris.isEmpty()) return@withContext false
        if (videoUris.size == 1) {
            // Если только один фрагмент, копируем его
            try {
                context.contentResolver.openInputStream(videoUris[0])?.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Error copying single video", e)
                return@withContext false
            }
        }

        var muxer: MediaMuxer? = null
        val extractors = mutableListOf<MediaExtractor>()
        
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // Получаем треки из первого видео
            val firstExtractor = MediaExtractor()
            context.contentResolver.openFileDescriptor(videoUris[0], "r")?.use { pfd ->
                firstExtractor.setDataSource(pfd.fileDescriptor)
            } ?: return@withContext false
            
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoSourceTrack = -1
            var audioSourceTrack = -1
            
            // Добавляем треки в muxer из первого видео
            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                
                when {
                    mime.startsWith("video/") && videoTrackIndex == -1 -> {
                        videoTrackIndex = muxer.addTrack(format)
                        videoSourceTrack = i
                        Log.d(TAG, "Added video track: $videoTrackIndex")
                    }
                    mime.startsWith("audio/") && audioTrackIndex == -1 -> {
                        audioTrackIndex = muxer.addTrack(format)
                        audioSourceTrack = i
                        Log.d(TAG, "Added audio track: $audioTrackIndex")
                    }
                }
            }
            
            muxer.start()
            
            var videoTimeOffsetUs = 0L
            var audioTimeOffsetUs = 0L
            
            // Обрабатываем каждое видео
            for ((index, uri) in videoUris.withIndex()) {
                val extractor = if (index == 0) {
                    firstExtractor
                } else {
                    MediaExtractor().apply {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            setDataSource(pfd.fileDescriptor)
                        }
                    }
                }
                extractors.add(extractor)
                
                // Находим треки в текущем видео
                var currentVideoTrack = -1
                var currentAudioTrack = -1
                
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    
                    when {
                        mime.startsWith("video/") && currentVideoTrack == -1 -> currentVideoTrack = i
                        mime.startsWith("audio/") && currentAudioTrack == -1 -> currentAudioTrack = i
                    }
                }
                
                // Копируем видео трек
                if (currentVideoTrack != -1 && videoTrackIndex != -1) {
                    val lastPts = copyTrack(
                        extractor = extractor,
                        muxer = muxer,
                        sourceTrackIndex = currentVideoTrack,
                        muxerTrackIndex = videoTrackIndex,
                        timeOffsetUs = videoTimeOffsetUs
                    )
                    videoTimeOffsetUs += lastPts
                }
                
                // Копируем аудио трек
                if (currentAudioTrack != -1 && audioTrackIndex != -1) {
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    val lastPts = copyTrack(
                        extractor = extractor,
                        muxer = muxer,
                        sourceTrackIndex = currentAudioTrack,
                        muxerTrackIndex = audioTrackIndex,
                        timeOffsetUs = audioTimeOffsetUs
                    )
                    audioTimeOffsetUs += lastPts
                }
                
                Log.d(TAG, "Merged fragment $index, video offset: $videoTimeOffsetUs, audio offset: $audioTimeOffsetUs")
            }
            
            muxer.stop()
            muxer.release()
            
            extractors.forEach { it.release() }
            
            Log.d(TAG, "Successfully merged ${videoUris.size} videos")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error merging videos", e)
            muxer?.release()
            extractors.forEach { 
                try { it.release() } catch (ex: Exception) { }
            }
            return@withContext false
        }
    }
    
    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        sourceTrackIndex: Int,
        muxerTrackIndex: Int,
        timeOffsetUs: Long
    ): Long {
        extractor.selectTrack(sourceTrackIndex)
        
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        var lastPresentationTimeUs = 0L
        
        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            
            val presentationTimeUs = extractor.sampleTime
            if (presentationTimeUs >= 0) {
                bufferInfo.presentationTimeUs = presentationTimeUs + timeOffsetUs
                bufferInfo.size = sampleSize
                bufferInfo.offset = 0
                bufferInfo.flags = extractor.sampleFlags
                
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                lastPresentationTimeUs = presentationTimeUs
            }
            
            extractor.advance()
        }
        
        extractor.unselectTrack(sourceTrackIndex)
        return lastPresentationTimeUs
    }

    suspend fun deleteVideos(context: Context, uris: List<Uri>) = withContext(Dispatchers.IO) {
        uris.forEach { uri ->
            try {
                context.contentResolver.delete(uri, null, null)
                Log.d(TAG, "Deleted video fragment: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting video: $uri", e)
            }
        }
    }
}
