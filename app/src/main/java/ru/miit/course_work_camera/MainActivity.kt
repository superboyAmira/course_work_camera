package ru.miit.course_work_camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPointFactory
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.miit.course_work_camera.model.MediaItem
import ru.miit.course_work_camera.model.MediaType
import ru.miit.course_work_camera.ui.CameraViewModel
import ru.miit.course_work_camera.ui.theme.Course_work_cameraTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Course_work_cameraTheme {
                CourseWorkApp()
            }
        }
    }
}

private enum class CameraMode {
    Photo, Video
}

private enum class AppDestination(val route: String) {
    Photo("photo"),
    Video("video"),
    Gallery("gallery")
}

@Composable
private fun CourseWorkApp(viewModel: CameraViewModel = viewModel(factory = CameraViewModel.Factory)) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val requiredPermissions = remember { buildRequiredPermissions() }
    var hasPermissions by remember { mutableStateOf(checkPermissions(context, requiredPermissions)) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermissions = requiredPermissions.all { permission ->
            result[permission] == true || ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        hasPermissions = checkPermissions(context, requiredPermissions)
    }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            viewModel.refreshMedia()
        }
    }

    DisposableEffect(lifecycleOwner, requiredPermissions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermissions = checkPermissions(context, requiredPermissions)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasPermissions) {
        PermissionScreen(
            permissions = requiredPermissions,
            onRequestPermissions = { launcher.launch(requiredPermissions.toTypedArray()) },
            onOpenSettings = { openSettings(context) }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp
            ) {
                val backStack by navController.currentBackStackEntryAsState()
                val currentRoute = backStack?.destination?.route ?: AppDestination.Photo.route
                AppDestination.values().forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        label = { Text(labelForDestination(destination)) },
                        icon = {
                            when (destination) {
                                AppDestination.Photo -> Icon(Icons.Default.Photo, contentDescription = null)
                                AppDestination.Video -> Icon(Icons.Default.Movie, contentDescription = null)
                                AppDestination.Gallery -> Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                            }
                        }
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Photo.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            composable(AppDestination.Photo.route) {
                CameraScreen(
                    mode = CameraMode.Photo,
                    viewModel = viewModel,
                    onOpenGallery = {
                        navController.navigate(AppDestination.Gallery.route)
                    },
                    snackbarHostState = snackbarHostState
                )
            }
            composable(AppDestination.Video.route) {
                CameraScreen(
                    mode = CameraMode.Video,
                    viewModel = viewModel,
                    onOpenGallery = {
                        navController.navigate(AppDestination.Gallery.route)
                    },
                    snackbarHostState = snackbarHostState
                )
            }
            composable(AppDestination.Gallery.route) {
                GalleryScreen(
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                    onBack = {
                        navController.navigate(AppDestination.Photo.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                inclusive = false
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen(
    permissions: List<String>,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val message = remember(context, permissions) {
        if (permissions.any { shouldShowRationale(context, it) }) {
            "Нужно разрешение камеры, микрофона и доступ к медиафайлам для работы приложения."
        } else {
            "Предоставьте доступ к камере, микрофону и медиафайлам."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Нужны разрешения",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermissions) {
            Text("Выдать разрешения")
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onOpenSettings) {
            Text("Открыть настройки")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScreen(
    viewModel: CameraViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit
) {
    val mediaItems by viewModel.media.collectAsState()
    var selectedItem by remember { mutableStateOf<MediaItem?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.refreshMedia()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Галерея") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            }
        )
        if (mediaItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Галерея пуста. Сделайте фото или видео.",
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                contentPadding = PaddingValues(12.dp)
            ) {
                items(mediaItems, key = { it.uri }) { item ->
                    MediaGridItem(item = item, onClick = { selectedItem = item })
                }
            }
        }
    }

    selectedItem?.let { item ->
        MediaPreviewDialog(
            item = item,
            onDelete = {
                viewModel.deleteMedia(item.uri) { success ->
                    if (!success) {
                        showMessage(scope, snackbarHostState, "Не удалось удалить файл")
                    }
                }
                selectedItem = null
            },
            onDismiss = { selectedItem = null }
        )
    }
}

@Composable
private fun MediaGridItem(item: MediaItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(6.dp)
            .fillMaxSize()
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize()
            )
            if (item.type == MediaType.Video) {
                Icon(
                    imageVector = Icons.Default.PlayCircleFilled,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaPreviewDialog(
    item: MediaItem,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Text("Удалить")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .height(480.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (item.type) {
                    MediaType.Photo -> {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.displayName,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 8.dp)
                        )
                    }
                    MediaType.Video -> {
                        AndroidView(
                            factory = { context ->
                                android.widget.VideoView(context).apply {
                                    setVideoURI(item.uri)
                                    setOnPreparedListener { player ->
                                        player.isLooping = true
                                        start()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 8.dp)
                        )
                    }
                }
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
    Text(
                    text = formatDate(item),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraScreen(
    mode: CameraMode,
    viewModel: CameraViewModel,
    onOpenGallery: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
        }
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var showFlash by remember { mutableStateOf(false) }
    var autofocusRequest by remember { mutableStateOf(UUID.randomUUID() to Offset.Unspecified) }
    val autofocusRequestId = autofocusRequest.first
    val showAutofocusIndicator = autofocusRequest.second.isSpecified
    val autofocusCoords = remember(autofocusRequestId) { autofocusRequest.second }
    val isRecording by viewModel.isRecording.collectAsState()
    val durationMs by viewModel.recordingDurationMs.collectAsState()
    val zoomState by controller.zoomState.observeAsState()
    val flashAlpha by animateFloatAsState(if (showFlash) 0.8f else 0f, label = "flash")

    if (showAutofocusIndicator) {
        LaunchedEffect(autofocusRequestId) {
            delay(1000)
            autofocusRequest = autofocusRequestId to Offset.Unspecified
        }
    }

    LaunchedEffect(cameraSelector, lifecycleOwner) {
        controller.cameraSelector = cameraSelector
        controller.bindToLifecycle(lifecycleOwner)
    }

    Scaffold(
//        contentColor = Color.Transparent,
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(if (mode == CameraMode.Photo) "Фото" else "Видео")
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                actions = {
                    IconButton(
                        onClick = {
                            val newSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }
                            
                            if (isRecording && mode == CameraMode.Video) {
                                // Во время записи видео - переключаем с остановкой/запуском
                                val hasAudio = checkPermissions(
                                    context,
                                    listOf(Manifest.permission.RECORD_AUDIO)
                                )
                                viewModel.switchCameraDuringRecording(
                                    controller = controller,
                                    hasAudioPermission = hasAudio,
                                    onCameraSwitched = {
                                        cameraSelector = newSelector
                                    },
                                    onError = { showMessage(scope, snackbarHostState, it) }
                                )
                            } else {
                                // Обычное переключение камеры
                                cameraSelector = newSelector
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Сменить камеру",
                            tint = Color.White
                        )
                    }
                }
            )
        },
        bottomBar = {
            CameraControls(
                mode = mode,
                isRecording = isRecording,
                onCapture = {
                    when (mode) {
                        CameraMode.Photo -> {
                            viewModel.takePhoto(
                                controller = controller,
                                onResult = {
                                    showFlash = true
                                    scope.launch {
                                        delay(120)
                                        showFlash = false
                                    }
                                },
                                onError = { showMessage(scope, snackbarHostState, it) }
                            )
                        }
                        CameraMode.Video -> {
                            if (isRecording) {
                                viewModel.stopVideoRecording(
                                    onFinalSaved = {
                                        showMessage(scope, snackbarHostState, "Видео сохранено")
                                    },
                                    onError = { showMessage(scope, snackbarHostState, it) }
                                )
                            } else {
                                val hasAudio = checkPermissions(
                                    context,
                                    listOf(Manifest.permission.RECORD_AUDIO)
                                )
                                viewModel.startVideoRecording(
                                    controller = controller,
                                    hasAudioPermission = hasAudio,
                                    onSaved = { /* Фрагмент сохранен */ },
                                    onError = { showMessage(scope, snackbarHostState, it) }
                                )
                            }
                        }
                    }
                },
                onGallery = onOpenGallery
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        this.controller = controller
                        previewView = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(controller, zoomState) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            val state = zoomState ?: return@detectTransformGestures
                            val newRatio = (state.zoomRatio * zoomChange)
                                .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                            controller.cameraControl?.setZoomRatio(newRatio)
                        }
                    }
                    .pointerInput(controller) {
                        detectTapGestures { offset ->
                            focusOnPoint(
                                offset = offset,
                                layoutSize = size,
                                previewView = previewView,
                                factory = previewView?.meteringPointFactory,
                                controller = controller
                            )
                            autofocusRequest = UUID.randomUUID() to offset
                        }
                    }
            )

            AnimatedVisibility(
                visible = mode == CameraMode.Video && isRecording,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                RecordingIndicator(durationMs = durationMs)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flashAlpha))
            )

            AnimatedVisibility(
                visible = showAutofocusIndicator && autofocusCoords.isSpecified,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val half = with(density) { 24.dp.toPx() }.roundToInt()
                        val coords = if (autofocusCoords.isSpecified) autofocusCoords else Offset.Zero
                        IntOffset(
                            coords.x.roundToInt() - half,
                            coords.y.roundToInt() - half
                        )
                    }
            ) {
                Spacer(
                    modifier = Modifier
                        .size(48.dp)
                        .border(2.dp, Color.White, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun RecordingIndicator(durationMs: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.medium
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(Color.Red, shape = MaterialTheme.shapes.small)
        )
        Text(
            text = formatDuration(durationMs),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CameraControls(
    mode: CameraMode,
    isRecording: Boolean,
    onCapture: () -> Unit,
    onGallery: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.Bottom
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onGallery) {
                Icon(Icons.Default.Photo, contentDescription = "Галерея")
            }

            CaptureButton(
                isRecording = isRecording,
                onClick = onCapture,
                mode = mode
            )

            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun CaptureButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    mode: CameraMode
) {
    val color = when {
        mode == CameraMode.Video && isRecording -> Color.Red
        else -> Color.White
    }
    val size = if (mode == CameraMode.Video && isRecording) 70.dp else 78.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(92.dp)
            .background(Color.White.copy(alpha = 0.2f), shape = MaterialTheme.shapes.extraLarge)
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .size(size),
            shape = MaterialTheme.shapes.extraLarge,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = color
            )
        ) {
            Icon(
                imageVector = if (mode == CameraMode.Video) Icons.Default.Movie else Icons.Default.CameraAlt,
                contentDescription = null,
                tint = color
            )
        }
    }
}

private fun focusOnPoint(
    offset: androidx.compose.ui.geometry.Offset,
    layoutSize: IntSize,
    previewView: PreviewView?,
    factory: MeteringPointFactory?,
    controller: LifecycleCameraController
) {
    val view = previewView ?: return
    val meteringFactory = factory ?: return
    val point = meteringFactory.createPoint(
        offset.x / layoutSize.width * view.width,
        offset.y / layoutSize.height * view.height
    )
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
        .setAutoCancelDuration(3, TimeUnit.SECONDS)
        .build()
    controller.cameraControl?.startFocusAndMetering(action)
}

private fun buildRequiredPermissions(): List<String> {
    val permissions = mutableListOf(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        permissions += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
    permissions += Manifest.permission.RECORD_AUDIO
    return permissions
}

private fun checkPermissions(context: Context, permissions: List<String>): Boolean {
    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun shouldShowRationale(context: Context, permission: String): Boolean {
    val activity = context as? ComponentActivity ?: return false
    return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

private fun openSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun labelForDestination(destination: AppDestination): String =
    when (destination) {
        AppDestination.Photo -> "Фото"
        AppDestination.Video -> "Видео"
        AppDestination.Gallery -> "Галерея"
    }

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatDate(item: MediaItem): String {
    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
        .withZone(ZoneId.systemDefault())
    return formatter.format(item.createdAt)
}

private fun showMessage(scope: CoroutineScope, snackbarHostState: SnackbarHostState, message: String) {
    scope.launch {
        snackbarHostState.showSnackbar(message)
    }
}