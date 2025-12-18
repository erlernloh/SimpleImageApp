package com.imagedit.app.ui.camera

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.imagedit.app.ui.camera.components.PhotoReviewBar
import com.imagedit.app.ui.camera.components.GridOverlay
import com.imagedit.app.ui.camera.GridType
import com.imagedit.app.ui.camera.TimerDuration
import com.imagedit.app.ui.camera.FlashMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onPhotoTaken: (String) -> Unit,
    onBatchEdit: (List<String>) -> Unit = {},
    onNavigateToUltraDetail: () -> Unit = {},
    viewModel: CameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState = viewModel.uiState
    val captureSession by viewModel.captureSession.collectAsState()
    
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var isCameraInitializing by remember { mutableStateOf(true) }
    var isCameraSetup by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    
    // Camera flip debouncing state
    var flipCameraJob by remember { mutableStateOf<Job?>(null) }
    var isCameraFlipping by remember { mutableStateOf(false) }
    
    // File picker launcher for selecting images from external storage
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Navigate to editor with selected file
            onPhotoTaken(it.toString())
        }
    }
    
    // Show error snackbar
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Error will be shown in UI
        }
    }
    
    // Resume camera when screen appears
    LaunchedEffect(Unit) {
        viewModel.resumeCamera()
    }
    
    // Pause camera on navigation to keep it alive (faster return)
    // Don't fully release to prevent expensive close/reopen cycles
    DisposableEffect(Unit) {
        onDispose {
            viewModel.pauseCamera()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photara", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    // Pick from files button
                    IconButton(onClick = { filePickerLauncher.launch("image/*") }) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = "Pick from Files",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    // Switch camera with debouncing
                    IconButton(
                        onClick = {
                            if (!isCameraFlipping) {
                                isCameraFlipping = true
                                flipCameraJob?.cancel()
                                flipCameraJob = viewModel.viewModelScope.launch {
                                    previewView?.surfaceProvider?.let { provider ->
                                        viewModel.switchCamera(lifecycleOwner, provider)
                                    }
                                    delay(500) // Prevent rapid flipping
                                    isCameraFlipping = false
                                }
                            }
                        },
                        enabled = !isCameraFlipping
                    ) {
                        Icon(
                            Icons.Default.FlipCameraAndroid,
                            contentDescription = "Switch Camera",
                            tint = if (isCameraFlipping) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    
                    // Ultra Detail+ button
                    IconButton(onClick = onNavigateToUltraDetail) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "Ultra Detail+",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    
                    // Gallery button
                    IconButton(onClick = onNavigateToGallery) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = "Gallery",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    // Exit button
                    IconButton(onClick = { showExitDialog = true }) {
                        Icon(
                            Icons.Default.ExitToApp,
                            contentDescription = "Exit App",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Camera Preview
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        previewView = this
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        
                        // Setup camera only once when preview is ready
                        post {
                            if (!isCameraSetup) {
                                val preview = Preview.Builder().build()
                                preview.setSurfaceProvider(surfaceProvider)
                                
                                viewModel.setupCamera(
                                    lifecycleOwner = lifecycleOwner,
                                    preview = preview,
                                    onCameraReady = { 
                                        isCameraInitializing = false
                                        isCameraSetup = true
                                    }
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // Prevent recreation on recomposition
                }
            )
            
            // Grid Overlay
            GridOverlay(
                gridType = uiState.gridType,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) {
                                viewModel.onPinchZoom(zoom)
                            }
                        }
                    }
            )
            
            // Photo Review Bar at top (below app bar)
            PhotoReviewBar(
                capturedPhotos = captureSession.capturedPhotos,
                selectedUri = captureSession.selectedPhotoUri,
                selectedUris = captureSession.selectedPhotoUris,
                isMultiSelectMode = captureSession.isMultiSelectMode,
                onPhotoSelected = { uri ->
                    viewModel.selectPhotoFromSession(uri)
                },
                onPhotoMultiSelected = { uri ->
                    viewModel.togglePhotoMultiSelection(uri)
                },
                onEditSelected = {
                    // Handle single photo edit (from either mode)
                    val uriToEdit = if (captureSession.isMultiSelectMode && captureSession.selectedPhotoUris.size == 1) {
                        captureSession.selectedPhotoUris.first()
                    } else {
                        captureSession.selectedPhotoUri
                    }
                    uriToEdit?.let { uriString ->
                        viewModel.endSession()
                        onPhotoTaken(uriString)
                    }
                },
                onBatchEditSelected = { uris ->
                    viewModel.endSession()
                    onBatchEdit(uris)
                },
                onToggleMultiSelect = {
                    viewModel.toggleMultiSelectMode()
                },
                onSelectAll = {
                    viewModel.selectAllPhotos()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 0.dp)
            )
            
            // Camera initialization overlay
            if (isCameraInitializing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Initializing camera...",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            
            // Loading overlay (for photo capture)
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Processing photo...",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            
            // Error message
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = { viewModel.clearError() }
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            
            // Camera controls at bottom
            CameraControls(
                onCaptureClick = { viewModel.capturePhoto() },
                onGridToggle = { viewModel.toggleGrid() },
                onTimerToggle = { viewModel.toggleTimer() },
                onFlashToggle = { viewModel.toggleFlashMode() },
                onSettingsClick = { showSettingsDialog = true },
                uiState = uiState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            
            // Timer indicator at top
            if (uiState.timerDuration != TimerDuration.OFF) {
                Text(
                    text = "Timer: ${uiState.timerDuration.label}",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Timer countdown overlay
            if (uiState.isTimerCountingDown) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${uiState.remainingSeconds}",
                        style = MaterialTheme.typography.displayLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 120.sp
                    )
                }
            }
        }
        
        // Exit confirmation dialog
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text("Exit App") },
                text = { Text("Are you sure you want to exit the app?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            (context as? android.app.Activity)?.finishAffinity()
                        }
                    ) {
                        Text("Exit")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showExitDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showSettingsDialog) {
            previewView?.let { preview ->
                CameraSettingsDialog(
                    settings = uiState.cameraSettings,
                    onDismiss = { showSettingsDialog = false },
                    onResolutionChange = { resolution ->
                        previewView?.surfaceProvider?.let { provider ->
                            viewModel.changeResolution(resolution, lifecycleOwner, provider)
                        }
                        showSettingsDialog = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CameraControls(
    onCaptureClick: () -> Unit,
    onGridToggle: () -> Unit,
    onTimerToggle: () -> Unit,
    onFlashToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    uiState: CameraUiState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Grid toggle button
        IconButton(
            onClick = onGridToggle,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Default.GridOn,
                contentDescription = "Toggle Grid",
                tint = if (uiState.gridType != GridType.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp)
            )
        }

        // Timer toggle button
        IconButton(
            onClick = onTimerToggle,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Default.Timer,
                contentDescription = "Toggle Timer",
                tint = if (uiState.timerDuration != TimerDuration.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp)
            )
        }

        // Capture button (center)
        FloatingActionButton(
            onClick = onCaptureClick,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else if (uiState.isTimerCountingDown) {
                Text(
                    text = "${uiState.remainingSeconds}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = "Capture Photo",
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // Flash toggle button
        IconButton(
            onClick = onFlashToggle,
            modifier = Modifier.size(48.dp)
        ) {
            when (uiState.flashMode) {
                FlashMode.OFF -> Icon(
                    Icons.Default.FlashOff,
                    contentDescription = "Flash Off",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                FlashMode.ON -> Icon(
                    Icons.Default.FlashOn,
                    contentDescription = "Flash On",
                    tint = MaterialTheme.colorScheme.primary
                )
                FlashMode.AUTO -> Icon(
                    Icons.Default.FlashAuto,
                    contentDescription = "Flash Auto",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Settings button
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun CameraSettingsDialog(
    settings: CameraSettings,
    onDismiss: () -> Unit,
    onResolutionChange: (Resolution) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera Settings") },
        text = {
            Column {
                Text("Photo Resolution", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                settings.availableResolutions.forEachIndexed { index, resolution ->
                    val isMax = index == 0
                    val mp = (resolution.width.toLong() * resolution.height / 1_000_000.0)
                    val ratioLabel = run {
                        val w = resolution.width.coerceAtLeast(resolution.height)
                        val h = resolution.height.coerceAtMost(resolution.width)
                        val r = w.toDouble() / h
                        val diff43 = kotlin.math.abs(r - 4.0 / 3.0)
                        val diff169 = kotlin.math.abs(r - 16.0 / 9.0)
                        if (diff43 <= diff169) "4:3" else "16:9"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResolutionChange(resolution) }
                            .padding(vertical = 8.dp)
                    ) {
                        RadioButton(
                            selected = settings.selectedResolution == resolution,
                            onClick = { onResolutionChange(resolution) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = buildString {
                                append("${resolution.width}x${resolution.height}")
                                append(" — ")
                                append(String.format("%.1fMP", mp))
                                append(" — ")
                                append(ratioLabel)
                                if (isMax) append(" (Max)")
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
