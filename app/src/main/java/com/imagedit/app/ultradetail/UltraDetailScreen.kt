/**
 * UltraDetailScreen.kt - UI for Ultra Detail+ feature
 * 
 * Provides camera preview with Ultra Detail+ capture button and
 * processing progress display.
 */

package com.imagedit.app.ultradetail

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.Image
import kotlinx.coroutines.launch

/**
 * Ultra Detail+ capture screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UltraDetailScreen(
    onNavigateBack: () -> Unit,
    onImageCaptured: (android.graphics.Bitmap) -> Unit,
    viewModel: UltraDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    val uiState by viewModel.uiState.collectAsState()
    
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var burstController by remember { mutableStateOf<BurstCaptureController?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    
    // Camera permission state
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }
    
    // Request permission if not granted
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    // Initialize ViewModel
    LaunchedEffect(Unit) {
        viewModel.initialize()
    }
    
    // Setup camera only when permission is granted
    LaunchedEffect(previewView, hasCameraPermission) {
        if (!hasCameraPermission) return@LaunchedEffect
        
        previewView?.let { view ->
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                cameraProvider = providerFuture.get()
                
                val preview = Preview.Builder()
                    .setTargetResolution(Size(1920, 1080))
                    .build()
                    .also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }
                
                // Create burst controller
                val config = BurstCaptureConfig(
                    frameCount = when (uiState.selectedPreset) {
                        UltraDetailPreset.FAST -> 6
                        UltraDetailPreset.BALANCED -> 8
                        UltraDetailPreset.MAX -> 12
                        UltraDetailPreset.ULTRA -> 10  // More frames for MFSR
                    },
                    targetResolution = Size(4000, 3000)
                )
                
                burstController = BurstCaptureController(context, config).apply {
                    setup(
                        cameraProvider!!,
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview
                    )
                }
                
            }, androidx.core.content.ContextCompat.getMainExecutor(context))
        }
    }
    
    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            burstController?.release()
            cameraProvider?.unbindAll()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "Ultra Detail+",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Preset selector
                    PresetSelector(
                        selectedPreset = uiState.selectedPreset,
                        onPresetSelected = { viewModel.setPreset(it) },
                        enabled = !uiState.isCapturing && !uiState.isProcessing
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Permission denied view
            if (!hasCameraPermission) {
                PermissionDeniedView(
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onNavigateBack = onNavigateBack,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // Camera preview or result
            else if (uiState.resultBitmap != null) {
                // Show result
                ResultView(
                    bitmap = uiState.resultBitmap!!,
                    processingTimeMs = uiState.processingTimeMs,
                    framesUsed = uiState.framesUsed,
                    detailTiles = uiState.detailTilesCount,
                    srTiles = uiState.srTilesProcessed,
                    preset = uiState.selectedPreset,
                    isSaving = uiState.isSaving,
                    isSaved = uiState.savedUri != null,
                    onSave = { 
                        viewModel.saveResult { uri ->
                            onImageCaptured(uiState.resultBitmap!!)
                        }
                    },
                    onRetake = { viewModel.clearResult() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Camera preview
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { previewView = it }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Capture controls
                CaptureControls(
                    isCapturing = uiState.isCapturing,
                    isProcessing = uiState.isProcessing,
                    captureProgress = uiState.captureProgress,
                    processingProgress = uiState.processingProgress,
                    statusMessage = uiState.statusMessage,
                    preset = uiState.selectedPreset,
                    onCaptureClick = {
                        burstController?.let { controller ->
                            viewModel.startCapture(controller)
                        }
                    },
                    onCancelClick = { viewModel.cancel() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                )
            }
            
            // Error display
            uiState.error?.let { error ->
                ErrorSnackbar(
                    message = error,
                    onDismiss = { viewModel.clearError() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }
}

/**
 * Preset selector dropdown
 */
@Composable
private fun PresetSelector(
    selectedPreset: UltraDetailPreset,
    onPresetSelected: (UltraDetailPreset) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled
        ) {
            Text(
                text = when (selectedPreset) {
                    UltraDetailPreset.FAST -> "Fast"
                    UltraDetailPreset.BALANCED -> "Balanced"
                    UltraDetailPreset.MAX -> "Max"
                    UltraDetailPreset.ULTRA -> "Ultra"
                }
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Fast", fontWeight = FontWeight.Bold)
                        Text("Burst merge only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                onClick = {
                    onPresetSelected(UltraDetailPreset.FAST)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Balanced", fontWeight = FontWeight.Bold)
                        Text("Merge + edge detection", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                onClick = {
                    onPresetSelected(UltraDetailPreset.BALANCED)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Max", fontWeight = FontWeight.Bold)
                        Text("Full pipeline with SR", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                onClick = {
                    onPresetSelected(UltraDetailPreset.MAX)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Ultra", fontWeight = FontWeight.Bold)
                        Text("MFSR 2x + AI refinement", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                onClick = {
                    onPresetSelected(UltraDetailPreset.ULTRA)
                    expanded = false
                }
            )
        }
    }
}

/**
 * Capture controls overlay
 */
@Composable
private fun CaptureControls(
    isCapturing: Boolean,
    isProcessing: Boolean,
    captureProgress: Float,
    processingProgress: Float,
    statusMessage: String,
    preset: UltraDetailPreset,
    onCaptureClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Status message
        AnimatedVisibility(
            visible = statusMessage.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text(
                    text = statusMessage,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        
        // Progress indicator
        AnimatedVisibility(
            visible = isCapturing || isProcessing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                LinearProgressIndicator(
                    progress = { if (isCapturing) captureProgress else processingProgress },
                    modifier = Modifier
                        .width(200.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                TextButton(onClick = onCancelClick) {
                    Text("Cancel", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        
        // Capture button
        AnimatedVisibility(
            visible = !isCapturing && !isProcessing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Preset indicator
                Text(
                    text = when (preset) {
                        UltraDetailPreset.FAST -> "6 frames"
                        UltraDetailPreset.BALANCED -> "8 frames"
                        UltraDetailPreset.MAX -> "12 frames + SR"
                        UltraDetailPreset.ULTRA -> "10 frames + MFSR 2x"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Capture button
                FloatingActionButton(
                    onClick = onCaptureClick,
                    modifier = Modifier.size(72.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "Capture Ultra Detail+",
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                Text(
                    text = "Ultra Detail+",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * Result view with captured image and stats
 */
@Composable
private fun ResultView(
    bitmap: android.graphics.Bitmap,
    processingTimeMs: Long,
    framesUsed: Int,
    detailTiles: Int,
    srTiles: Int,
    preset: UltraDetailPreset,
    isSaving: Boolean = false,
    isSaved: Boolean = false,
    onSave: () -> Unit,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Image
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured image",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
        
        // Stats card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Ultra Detail+ Result",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatItem("Time", "${processingTimeMs}ms")
                    StatItem("Frames", "$framesUsed")
                    StatItem("Resolution", "${bitmap.width}x${bitmap.height}")
                }
                
                if (preset == UltraDetailPreset.MAX) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("Detail tiles", "$detailTiles")
                        StatItem("SR tiles", "$srTiles")
                        StatItem("Preset", "Max")
                    }
                }
            }
        }
        
        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OutlinedButton(
                onClick = onRetake,
                enabled = !isSaving,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retake")
            }
            
            Button(
                onClick = onSave,
                enabled = !isSaving && !isSaved,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Saving...")
                } else if (isSaved) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Saved!")
                } else {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save")
                }
            }
        }
    }
}

/**
 * Stat item display
 */
@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Error snackbar
 */
@Composable
private fun ErrorSnackbar(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Snackbar(
        modifier = modifier,
        action = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    ) {
        Text(message)
    }
}

/**
 * Permission denied view
 */
@Composable
private fun PermissionDeniedView(
    onRequestPermission: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Ultra Detail+ needs camera access to capture burst photos for enhanced image processing.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onRequestPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Grant Camera Permission")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedButton(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Go Back")
        }
    }
}
