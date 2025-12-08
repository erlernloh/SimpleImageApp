package com.imagedit.app.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.imagedit.app.domain.model.AdjustmentParameters
import com.imagedit.app.domain.model.FilmGrain
import com.imagedit.app.domain.model.LensEffects
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchEditorScreen(
    photoUris: List<String>,
    onNavigateBack: () -> Unit,
    viewModel: BatchEditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var showSaveDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<EditorCategory?>(null) }
    
    // Load photos on first composition
    LaunchedEffect(photoUris) {
        viewModel.loadPhotos(photoUris)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Batch Edit (${uiState.photos.size} photos)",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.hasUnsavedChanges) {
                            showDiscardDialog = true
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Reset button
                    IconButton(
                        onClick = { viewModel.resetToOriginal() },
                        enabled = uiState.hasUnsavedChanges
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset")
                    }
                    
                    // Save button
                    Button(
                        onClick = { showSaveDialog = true },
                        enabled = uiState.hasUnsavedChanges && !uiState.isSaving,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save All")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Stacked Photo Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (uiState.photos.isNotEmpty()) {
                    StackedPhotoPreview(
                        photos = uiState.photos,
                        currentIndex = uiState.currentIndex,
                        onIndexChange = { viewModel.setCurrentIndex(it) },
                        isProcessing = uiState.isProcessing
                    )
                }
                
                // Processing indicator
                if (uiState.isProcessing) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Applying to all photos...",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
            
            // Photo thumbnail strip
            PhotoThumbnailStrip(
                photos = uiState.photos,
                currentIndex = uiState.currentIndex,
                onPhotoSelected = { viewModel.setCurrentIndex(it) }
            )
            
            // Editor controls
            EditorControlsPanel(
                adjustments = uiState.adjustments,
                filmGrain = uiState.filmGrain,
                lensEffects = uiState.lensEffects,
                presets = uiState.availablePresets,
                selectedPreset = uiState.selectedPreset,
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                onAdjustmentsChange = { viewModel.updateAdjustments(it) },
                onFilmGrainChange = { viewModel.updateFilmGrain(it) },
                onLensEffectsChange = { viewModel.updateLensEffects(it) },
                onPresetSelected = { viewModel.applyPreset(it) },
                onRotateLeft = { viewModel.rotateLeft() },
                onRotateRight = { viewModel.rotateRight() },
                onFlipHorizontal = { viewModel.flipHorizontally() },
                onFlipVertical = { viewModel.flipVertically() }
            )
        }
    }
    
    // Save confirmation dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save All Photos") },
            text = { 
                Text("Apply edits to all ${uiState.photos.size} photos and save them to gallery?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveDialog = false
                        viewModel.saveAllPhotos { success, failed ->
                            scope.launch {
                                // Show result and navigate back
                                if (failed == 0) {
                                    onNavigateBack()
                                }
                            }
                        }
                    }
                ) {
                    Text("Save All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Discard changes dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard Changes?") },
            text = { Text("You have unsaved changes. Are you sure you want to discard them?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardDialog = false
                        onNavigateBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }
    
    // Saving progress dialog
    if (uiState.isSaving) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Saving Photos") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LinearProgressIndicator(
                        progress = { uiState.saveProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${uiState.savedCount} of ${uiState.photos.size} saved",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = { }
        )
    }
    
    // Error snackbar
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Error will be shown
        }
    }
}

@Composable
private fun StackedPhotoPreview(
    photos: List<BatchPhoto>,
    currentIndex: Int,
    onIndexChange: (Int) -> Unit,
    isProcessing: Boolean
) {
    var dragOffset by remember { mutableStateOf(0f) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(photos.size) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // Determine if we should change photo
                        if (dragOffset.absoluteValue > 100) {
                            if (dragOffset > 0 && currentIndex > 0) {
                                onIndexChange(currentIndex - 1)
                            } else if (dragOffset < 0 && currentIndex < photos.size - 1) {
                                onIndexChange(currentIndex + 1)
                            }
                        }
                        dragOffset = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragOffset += dragAmount
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Show stacked photos (current + neighbors)
        val visibleRange = maxOf(0, currentIndex - 2)..minOf(photos.size - 1, currentIndex + 2)
        
        visibleRange.reversed().forEach { index ->
            val photo = photos[index]
            val offset = index - currentIndex
            val isCurrentPhoto = index == currentIndex
            
            val scale by animateFloatAsState(
                targetValue = when {
                    isCurrentPhoto -> 1f
                    offset.absoluteValue == 1 -> 0.9f
                    else -> 0.8f
                },
                animationSpec = tween(200),
                label = "scale"
            )
            
            val alpha by animateFloatAsState(
                targetValue = when {
                    isCurrentPhoto -> 1f
                    offset.absoluteValue == 1 -> 0.6f
                    else -> 0.3f
                },
                animationSpec = tween(200),
                label = "alpha"
            )
            
            val translationX by animateFloatAsState(
                targetValue = offset * 40f + (if (isCurrentPhoto) dragOffset * 0.3f else 0f),
                animationSpec = tween(200),
                label = "translationX"
            )
            
            val translationY by animateFloatAsState(
                targetValue = offset.absoluteValue * 20f,
                animationSpec = tween(200),
                label = "translationY"
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(4f / 3f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        this.translationX = translationX
                        this.translationY = translationY
                    }
                    .shadow(
                        elevation = if (isCurrentPhoto) 8.dp else 4.dp,
                        shape = RoundedCornerShape(12.dp)
                    ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        photo.isLoading -> {
                            CircularProgressIndicator()
                        }
                        photo.error != null -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.BrokenImage,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    "Failed to load",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        photo.processedBitmap != null -> {
                            androidx.compose.foundation.Image(
                                bitmap = photo.processedBitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }
        
        // Navigation arrows
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Previous button
            IconButton(
                onClick = { if (currentIndex > 0) onIndexChange(currentIndex - 1) },
                enabled = currentIndex > 0,
                modifier = Modifier
                    .padding(8.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        CircleShape
                    )
            ) {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = "Previous",
                    modifier = Modifier.size(32.dp)
                )
            }
            
            // Next button
            IconButton(
                onClick = { if (currentIndex < photos.size - 1) onIndexChange(currentIndex + 1) },
                enabled = currentIndex < photos.size - 1,
                modifier = Modifier
                    .padding(8.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        CircleShape
                    )
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Next",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        
        // Photo counter
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
        ) {
            Text(
                "${currentIndex + 1} / ${photos.size}",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun PhotoThumbnailStrip(
    photos: List<BatchPhoto>,
    currentIndex: Int,
    onPhotoSelected: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Auto-scroll to current photo
    LaunchedEffect(currentIndex) {
        scope.launch {
            listState.animateScrollToItem(
                index = maxOf(0, currentIndex - 1),
                scrollOffset = 0
            )
        }
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            itemsIndexed(photos) { index, photo ->
                val isSelected = index == currentIndex
                
                Card(
                    modifier = Modifier
                        .size(60.dp)
                        .clickable { onPhotoSelected(index) }
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(8.dp)
                                )
                            } else Modifier
                        ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            photo.isLoading -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            photo.processedBitmap != null -> {
                                androidx.compose.foundation.Image(
                                    bitmap = photo.processedBitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            else -> {
                                Icon(
                                    Icons.Default.BrokenImage,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        
                        // Index badge
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(2.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        ) {
                            Text(
                                "${index + 1}",
                                modifier = Modifier.padding(4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

enum class EditorCategory {
    ADJUSTMENTS,
    FILTERS,
    TRANSFORM
}

@Composable
private fun EditorControlsPanel(
    adjustments: AdjustmentParameters,
    filmGrain: FilmGrain,
    lensEffects: LensEffects,
    presets: List<com.imagedit.app.domain.repository.FilterPreset>,
    selectedPreset: com.imagedit.app.domain.repository.FilterPreset?,
    selectedCategory: EditorCategory?,
    onCategorySelected: (EditorCategory?) -> Unit,
    onAdjustmentsChange: (AdjustmentParameters) -> Unit,
    onFilmGrainChange: (FilmGrain) -> Unit,
    onLensEffectsChange: (LensEffects) -> Unit,
    onPresetSelected: (com.imagedit.app.domain.repository.FilterPreset) -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Category tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            EditorCategory.values().forEach { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { 
                        onCategorySelected(if (selectedCategory == category) null else category)
                    },
                    label = { 
                        Text(
                            when (category) {
                                EditorCategory.ADJUSTMENTS -> "Adjust"
                                EditorCategory.FILTERS -> "Filters"
                                EditorCategory.TRANSFORM -> "Transform"
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            when (category) {
                                EditorCategory.ADJUSTMENTS -> Icons.Default.Tune
                                EditorCategory.FILTERS -> Icons.Default.FilterVintage
                                EditorCategory.TRANSFORM -> Icons.Default.Transform
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
        
        // Category content
        selectedCategory?.let { category ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                when (category) {
                    EditorCategory.ADJUSTMENTS -> {
                        AdjustmentsPanel(
                            adjustments = adjustments,
                            onAdjustmentsChange = onAdjustmentsChange
                        )
                    }
                    EditorCategory.FILTERS -> {
                        FiltersPanel(
                            presets = presets,
                            selectedPreset = selectedPreset,
                            onPresetSelected = onPresetSelected
                        )
                    }
                    EditorCategory.TRANSFORM -> {
                        TransformPanel(
                            onRotateLeft = onRotateLeft,
                            onRotateRight = onRotateRight,
                            onFlipHorizontal = onFlipHorizontal,
                            onFlipVertical = onFlipVertical
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdjustmentsPanel(
    adjustments: AdjustmentParameters,
    onAdjustmentsChange: (AdjustmentParameters) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AdjustmentSlider(
            label = "Brightness",
            value = adjustments.brightness,
            onValueChange = { onAdjustmentsChange(adjustments.copy(brightness = it)) }
        )
        AdjustmentSlider(
            label = "Contrast",
            value = adjustments.contrast,
            onValueChange = { onAdjustmentsChange(adjustments.copy(contrast = it)) }
        )
        AdjustmentSlider(
            label = "Saturation",
            value = adjustments.saturation,
            onValueChange = { onAdjustmentsChange(adjustments.copy(saturation = it)) }
        )
        AdjustmentSlider(
            label = "Warmth",
            value = adjustments.warmth,
            onValueChange = { onAdjustmentsChange(adjustments.copy(warmth = it)) }
        )
        AdjustmentSlider(
            label = "Exposure",
            value = adjustments.exposure,
            onValueChange = { onAdjustmentsChange(adjustments.copy(exposure = it)) }
        )
    }
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.width(80.dp),
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${(value * 100).toInt()}",
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun FiltersPanel(
    presets: List<com.imagedit.app.domain.repository.FilterPreset>,
    selectedPreset: com.imagedit.app.domain.repository.FilterPreset?,
    onPresetSelected: (com.imagedit.app.domain.repository.FilterPreset) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(presets.size) { index ->
            val preset = presets[index]
            val isSelected = preset.id == selectedPreset?.id
            
            Card(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .clickable { onPresetSelected(preset) }
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(8.dp)
                            )
                        } else Modifier
                    ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.FilterVintage,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary 
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        preset.name,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun TransformPanel(
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransformButton(
            icon = Icons.Default.RotateLeft,
            label = "Rotate Left",
            onClick = onRotateLeft
        )
        TransformButton(
            icon = Icons.Default.RotateRight,
            label = "Rotate Right",
            onClick = onRotateRight
        )
        TransformButton(
            icon = Icons.Default.Flip,
            label = "Flip H",
            onClick = onFlipHorizontal
        )
        TransformButton(
            icon = Icons.Default.FlipCameraAndroid,
            label = "Flip V",
            onClick = onFlipVertical
        )
    }
}

@Composable
private fun TransformButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
