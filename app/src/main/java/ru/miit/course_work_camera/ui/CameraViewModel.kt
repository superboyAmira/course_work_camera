package ru.miit.course_work_camera.ui

import android.app.Application
import android.net.Uri
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.miit.course_work_camera.data.MediaRepository
import ru.miit.course_work_camera.model.MediaItem

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())
    val media: StateFlow<List<MediaItem>> = _media.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private var activeRecording: Recording? = null

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
                    _isRecording.value = true
                    _recordingDurationMs.value = 0L
                }

                is VideoRecordEvent.Status -> {
                    _recordingDurationMs.value =
                        event.recordingStats.recordedDurationNanos / 1_000_000
                }

                is VideoRecordEvent.Finalize -> {
                    _isRecording.value = false
                    _recordingDurationMs.value = 0L
                    activeRecording = null
                    if (!event.hasError()) {
                        refreshMedia()
                        onSaved(event.outputResults.outputUri)
                    } else {
                        onError(event.error.toString())
                    }
                }
            }
        }
    }

    fun stopVideoRecording() {
        activeRecording?.stop()
    }

    override fun onCleared() {
        activeRecording?.close()
        cameraExecutor.shutdown()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
                CameraViewModel(application)
            }
        }
    }
}
