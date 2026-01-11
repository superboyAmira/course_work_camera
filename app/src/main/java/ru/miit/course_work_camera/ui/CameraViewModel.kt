package ru.miit.course_work_camera.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.video.AudioConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.miit.course_work_camera.data.MediaRepository
import ru.miit.course_work_camera.model.MediaItem
import ru.miit.course_work_camera.utils.VideoMerger

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
        
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
                CameraViewModel(application)
            }
        }
    }

    private val repository = MediaRepository(application)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())
    val media: StateFlow<List<MediaItem>> = _media.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private var activeRecording: Recording? = null
    private val videoFragments = mutableListOf<Uri>()
    private var totalRecordingDuration = 0L

    fun refreshMedia() {
        viewModelScope.launch {
            _media.value = repository.loadMedia()
        }
    }

    fun deleteMedia(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.delete(uri)
            if (result) {
                refreshMedia()
            }
            onResult(result)
        }
    }

    fun takePhoto(
        controller: LifecycleCameraController,
        onResult: (Uri?) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = repository.createImageRequest()
        controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
        controller.takePicture(
            request.outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri ?: request.fallbackUri
                    refreshMedia()
                    onResult(uri)
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception.message ?: "Не удалось сохранить фото")
                }
            }
        )
    }

    fun startVideoRecording(
        controller: LifecycleCameraController,
        hasAudioPermission: Boolean,
        onSaved: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "startVideoRecording: fragments count = ${videoFragments.size}")
        controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
        val outputOptions = repository.createVideoRequest()
        val audioConfig = AudioConfig.create(hasAudioPermission)
        activeRecording?.stop()
        activeRecording = controller.startRecording(
            outputOptions,
            audioConfig,
            ContextCompat.getMainExecutor(getApplication())
        ) { event: VideoRecordEvent ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    Log.d(TAG, "Recording started")
                    _isRecording.value = true
                    if (videoFragments.isEmpty()) {
                        _recordingDurationMs.value = 0L
                        totalRecordingDuration = 0L
                    }
                }

                is VideoRecordEvent.Status -> {
                    _recordingDurationMs.value =
                        totalRecordingDuration + (event.recordingStats.recordedDurationNanos / 1_000_000)
                }

                is VideoRecordEvent.Finalize -> {
                    Log.d(TAG, "Recording finalized, hasError = ${event.hasError()}, pendingRecording = ${pendingRecording != null}, stopCallback = ${stopRecordingCallback != null}")
                    activeRecording = null
                    
                    if (!event.hasError()) {
                        val uri = event.outputResults.outputUri
                        val duration = event.recordingStats.recordedDurationNanos / 1_000_000
                        totalRecordingDuration += duration
                        videoFragments.add(uri)
                        Log.d(TAG, "Fragment saved: $uri, total fragments: ${videoFragments.size}, duration: ${duration}ms")
                        onSaved(uri)
                        
                        // Если есть отложенная запись (переключение камеры), продолжаем
                        if (pendingRecording != null) {
                            resumeRecordingIfPending()
                        } else if (stopRecordingCallback != null) {
                            // Пользователь остановил запись - финализируем и склеиваем
                            _isRecording.value = false
                            finalizeVideoRecording()
                        } else {
                            // Обычная остановка (не должно происходить)
                            _isRecording.value = false
                        }
                    } else {
                        _isRecording.value = false
                        pendingRecording = null
                        stopRecordingCallback = null
                        Log.e(TAG, "Recording error: ${event.error}")
                        onError(event.error.toString())
                    }
                }
            }
        }
    }

    private var stopRecordingCallback: Pair<(Uri) -> Unit, (String) -> Unit>? = null
    
    fun stopVideoRecording(onFinalSaved: (Uri) -> Unit, onError: (String) -> Unit) {
        Log.d(TAG, "stopVideoRecording: fragments count = ${videoFragments.size}, pendingRecording = ${pendingRecording != null}")
        pendingRecording = null // Отменяем отложенную запись
        stopRecordingCallback = onFinalSaved to onError
        activeRecording?.stop()
    }
    
    private fun finalizeVideoRecording() {
        val (onFinalSaved, onError) = stopRecordingCallback ?: return
        stopRecordingCallback = null
        
        if (videoFragments.isNotEmpty()) {
            viewModelScope.launch {
                // Даем небольшую задержку чтобы убедиться что последний фрагмент сохранен
                kotlinx.coroutines.delay(300)
                
                try {
                    Log.d(TAG, "Starting video merge for ${videoFragments.size} fragments")
                    val outputFile = File(
                        getApplication<Application>().cacheDir,
                        "merged_${System.currentTimeMillis()}.mp4"
                    )
                    val success = VideoMerger.mergeVideos(
                        getApplication(),
                        videoFragments.toList(), // Копируем список
                        outputFile
                    )
                    if (success) {
                        Log.d(TAG, "Video merge successful, saving to MediaStore")
                        // Сохранить склеенное видео в MediaStore
                        val finalUri = repository.saveVideoToMediaStore(outputFile)
                        Log.d(TAG, "Saved to MediaStore: $finalUri, deleting fragments")
                        VideoMerger.deleteVideos(getApplication(), videoFragments.toList())
                        outputFile.delete()
                        videoFragments.clear()
                        totalRecordingDuration = 0L
                        _recordingDurationMs.value = 0L
                        refreshMedia()
                        onFinalSaved(finalUri)
                    } else {
                        Log.e(TAG, "Video merge failed")
                        videoFragments.clear()
                        totalRecordingDuration = 0L
                        _recordingDurationMs.value = 0L
                        onError("Не удалось склеить видео")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during video merge", e)
                    videoFragments.clear()
                    totalRecordingDuration = 0L
                    _recordingDurationMs.value = 0L
                    onError(e.message ?: "Ошибка при склейке видео")
                }
            }
        } else {
            Log.d(TAG, "No fragments to merge")
            totalRecordingDuration = 0L
            _recordingDurationMs.value = 0L
        }
    }

    private var pendingRecording: PendingRecording? = null
    
    private data class PendingRecording(
        val controller: LifecycleCameraController,
        val hasAudioPermission: Boolean,
        val onError: (String) -> Unit
    )
    
    fun switchCameraDuringRecording(
        controller: LifecycleCameraController,
        hasAudioPermission: Boolean,
        onCameraSwitched: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!_isRecording.value) return
        
        Log.d(TAG, "switchCameraDuringRecording: stopping current recording")
        // Сохраняем запрос на новую запись
        pendingRecording = PendingRecording(controller, hasAudioPermission, onError)
        
        // Останавливаем текущую запись - в onFinalize начнется новая
        activeRecording?.stop()
        
        // Переключаем камеру в UI немедленно
        viewModelScope.launch {
            kotlinx.coroutines.delay(100)
            onCameraSwitched()
        }
    }
    
    private fun resumeRecordingIfPending() {
        val pending = pendingRecording ?: return
        pendingRecording = null
        
        Log.d(TAG, "resumeRecordingIfPending: starting new recording, fragments: ${videoFragments.size}")
        viewModelScope.launch {
            // Даем время камере переключиться
            kotlinx.coroutines.delay(800)
            startVideoRecording(
                controller = pending.controller,
                hasAudioPermission = pending.hasAudioPermission,
                onSaved = { /* Фрагмент сохранен */ },
                onError = pending.onError
            )
        }
    }

    override fun onCleared() {
        activeRecording?.close()
        cameraExecutor.shutdown()
        super.onCleared()
    }
}
