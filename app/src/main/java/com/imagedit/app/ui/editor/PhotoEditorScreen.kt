package com.imagedit.app.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.imagedit.app.domain.repository.FilterPreset
import com.imagedit.app.ui.common.ErrorDialog
import com.imagedit.app.ui.editor.CropAspectRatio
import com.imagedit.app.ui.editor.components.CropOverlay
import com.imagedit.app.ui.editor.components.BeforeAfterComparison
import com.imagedit.app.ui.editor.components.SmartEnhanceButton
import com.imagedit.app.ui.editor.components.SceneDetectionCard
import com.imagedit.app.ui.editor.components.EditorCategoryCard
import com.imagedit.app.ui.editor.components.LandscapeEnhancementCard
import com.imagedit.app.ui.editor.components.SmartEnhancementErrorDialog
import com.imagedit.app.ui.editor.components.SmartEnhancementInlineProgress
import com.imagedit.app.ui.editor.components.HealingBrushOverlay
import com.imagedit.app.domain.model.HealingBrush
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhotoEditorScreen(
    photoUri: String,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    viewModel: PhotoEditorViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var showEditPresetDialog by remember { mutableStateOf(false) }
    var showDuplicatePresetDialog by remember { mutableStateOf(false) }
    var showDeletePresetDialog by remember { mutableStateOf(false) }
    var selectedPresetForAction by remember { mutableStateOf<FilterPreset?>(null) }
    var presetNameInput by remember { mutableStateOf("") }
    
    // Load photo when screen opens
    // Delay to allow navigation animation to complete
    LaunchedEffect(photoUri) {
        kotlinx.coroutines.delay(150) // Let navigation animation finish
        viewModel.loadPhoto(photoUri)
    }
    
    // Handle back navigation with unsaved changes
    val handleBackNavigation = {
        if (uiState.hasUnsavedChanges) {
            showUnsavedDialog = true
        } else {
            onNavigateBack()
        }
    }
    
    Scaffold(
        topBar = {
            Column {
                // Title bar with back button and title
                TopAppBar(
                    title = { 
                        Text(
                            text = "Edit Photo",
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = handleBackNavigation) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
                
                // Action bar with editing controls
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left side: Undo, Redo, Reset
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Undo
                            TextButton(
                                onClick = { viewModel.undo() },
                                enabled = uiState.undoStack.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.Undo,
                                    contentDescription = "Undo",
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Undo")
                            }
                            
                            // Redo
                            TextButton(
                                onClick = { viewModel.redo() },
                                enabled = uiState.redoStack.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.Redo,
                                    contentDescription = "Redo",
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Redo")
                            }
                            
                            // Reset
                            TextButton(
                                onClick = { viewModel.resetToOriginal() }
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Reset",
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset")
                            }
                        }
                        
                        // Right side: Save button
                        Button(
                            onClick = { viewModel.savePhoto { onNavigateBack() } },
                            enabled = uiState.hasUnsavedChanges && !uiState.isLoading
                        ) {
                            if (uiState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Save")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Photo preview - Fixed height to always be visible
            var imageSize by remember { mutableStateOf(Size.Zero) }
            var imageOffset by remember { mutableStateOf(Offset.Zero) }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    uiState.processedBitmap != null -> {
                        val bitmap = uiState.processedBitmap
                        
                        // Before/After comparison or regular image display
                        BeforeAfterComparison(
                            originalBitmap = uiState.originalBitmap,
                            processedBitmap = bitmap,
                            isShowingComparison = uiState.isShowingBeforeAfter,
                            transitionProgress = uiState.beforeAfterTransitionProgress,
                            onToggleComparison = { viewModel.toggleBeforeAfterComparison() },
                            onProgressChange = { progress -> viewModel.updateBeforeAfterProgress(progress) },
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .onGloballyPositioned { coordinates ->
                                    imageSize = coordinates.size.toSize()
                                    // Use local position (0,0) since overlay is in same parent
                                    imageOffset = Offset.Zero
                                }
                        )
                        
                        // Crop overlay (use actual displayed content rect under ContentScale.Fit)
                        if (uiState.cropState.isActive && imageSize != Size.Zero) {
                            val containerW = imageSize.width
                            val containerH = imageSize.height
                            val bmpW = bitmap?.width?.toFloat() ?: 0f
                            val bmpH = bitmap?.height?.toFloat() ?: 0f
                            val imageAspect = if (bmpH != 0f) bmpW / bmpH else 1f
                            val containerAspect = if (containerH != 0f) containerW / containerH else imageAspect

                            val contentRect: Rect = if (containerW > 0f && containerH > 0f) {
                                if (imageAspect > containerAspect) {
                                    val fitWidth = containerW
                                    val fitHeight = fitWidth / imageAspect
                                    val offsetX = 0f
                                    val offsetY = (containerH - fitHeight) / 2f
                                    Rect(offset = Offset(offsetX, offsetY), size = Size(fitWidth, fitHeight))
                                } else {
                                    val fitHeight = containerH
                                    val fitWidth = fitHeight * imageAspect
                                    val offsetY = 0f
                                    val offsetX = (containerW - fitWidth) / 2f
                                    Rect(offset = Offset(offsetX, offsetY), size = Size(fitWidth, fitHeight))
                                }
                            } else {
                                Rect(Offset.Zero, imageSize)
                            }

                            // Update bounds in state to maintain normalized mapping when contentRect changes
                            LaunchedEffect(contentRect) {
                                viewModel.setCropImageBounds(contentRect)
                            }

                            CropOverlay(
                                cropRect = uiState.cropState.cropRect ?: contentRect,
                                imageBounds = contentRect,
                                aspectRatio = uiState.cropState.aspectRatio,
                                onCropRectChange = { viewModel.updateCropRect(it) }
                            )
                        }
                        
                        // Healing brush overlay
                        if (uiState.isHealingToolActive && imageSize != Size.Zero) {
                            HealingBrushOverlay(
                                brushSettings = uiState.healingBrushSettings,
                                onBrushStart = { x, y, pressure ->
                                    // Convert screen coordinates to image coordinates
                                    // This is a simplified implementation
                                    viewModel.startHealingStroke(x, y, pressure)
                                },
                                onBrushMove = { x, y, pressure ->
                                    viewModel.addHealingStrokePoint(x, y, pressure)
                                },
                                onBrushEnd = {
                                    viewModel.finishHealingStroke()
                                },
                                strokePath = null, // TODO: Convert strokes to path
                                currentStrokePath = null, // TODO: Convert current stroke to path
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        
                        // Processing overlay
                        if (uiState.isProcessing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                        RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    
                    else -> {
                        Text(
                            text = "Loading photo...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Controls section with categories
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Smart Enhancement section - AT TOP
                var smartEnhanceExpanded by remember { mutableStateOf(true) }
                EditorCategoryCard(
                    title = "Smart Enhancement",
                    isExpanded = smartEnhanceExpanded,
                    onToggle = { smartEnhanceExpanded = !smartEnhanceExpanded }
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Smart Enhance button
                        SmartEnhanceButton(
                            onClick = { viewModel.applySmartEnhancement() },
                            isProcessing = uiState.isProcessing,
                            isApplied = uiState.smartEnhancementApplied,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        // Information text
                        Text(
                            text = "Automatically enhance your photo using intelligent algorithms that analyze exposure, color balance, and scene characteristics.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        
                        // Before/After toggle (when enhancement is applied)
                        if (uiState.smartEnhancementApplied) {
                            OutlinedButton(
                                onClick = { viewModel.toggleBeforeAfterComparison() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (uiState.isShowingBeforeAfter) 
                                        Icons.Default.VisibilityOff 
                                    else 
                                        Icons.Default.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (uiState.isShowingBeforeAfter) 
                                        "Hide Comparison" 
                                    else 
                                        "Show Before/After"
                                )
                            }
                        }
                    }
                }
                
                // Scene Detection section
                SceneDetectionCard(
                    sceneAnalysis = uiState.sceneAnalysis,
                    isAnalyzing = uiState.isAnalyzingScene,
                    error = uiState.sceneAnalysisError,
                    onApplyEnhancement = { suggestion ->
                        viewModel.applySceneBasedEnhancement(suggestion)
                    },
                    onManualSceneSelect = { sceneType ->
                        viewModel.overrideSceneType(sceneType)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Portrait Enhancement section
                var portraitExpanded by remember { mutableStateOf(false) }
                EditorCategoryCard(
                    title = "Portrait Enhancement",
                    isExpanded = portraitExpanded,
                    onToggle = { portraitExpanded = !portraitExpanded }
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Portrait Enhancement toggle button
                        Button(
                            onClick = { viewModel.togglePortraitEnhancement() },
                            enabled = !uiState.isProcessing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (uiState.portraitEnhancementApplied) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            if (uiState.isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(
                                    imageVector = if (uiState.portraitEnhancementApplied) 
                                        Icons.Default.Face 
                                    else 
                                        Icons.Default.Face,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                if (uiState.portraitEnhancementApplied) 
                                    "Portrait Enhanced" 
                                else 
                                    "Enhance Portrait"
                            )
                        }
                        
                        // Intensity slider (when portrait enhancement is active)
                        if (uiState.portraitEnhancementApplied || uiState.isPortraitEnhancementActive) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Intensity",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${(uiState.portraitEnhancementIntensity * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                
                                Slider(
                                    value = uiState.portraitEnhancementIntensity,
                                    onValueChange = { viewModel.updatePortraitEnhancementIntensity(it) },
                                    valueRange = 0f..1f,
                                    steps = 19, // 20 steps (0%, 5%, 10%, ..., 100%)
                                    enabled = !uiState.isProcessing,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        
                        // Information text
                        Text(
                            text = "Enhance portraits with intelligent skin smoothing, eye brightening, and natural skin tone correction. Works best with photos containing faces.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        
                        // Before/After toggle (when portrait enhancement is applied)
                        if (uiState.portraitEnhancementApplied) {
                            OutlinedButton(
                                onClick = { viewModel.toggleBeforeAfterComparison() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (uiState.isShowingBeforeAfter) 
                                        Icons.Default.VisibilityOff 
                                    else 
                                        Icons.Default.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (uiState.isShowingBeforeAfter) 
                                        "Hide Comparison" 
                                    else 
                                        "Show Before/After"
                                )
                            }
                        }
                    }
                }
                
                // Healing Tool section
                var healingExpanded by remember { mutableStateOf(false) }
                EditorCategoryCard(
                    title = "Healing Tool",
                    isExpanded = healingExpanded,
                    onToggle = { healingExpanded = !healingExpanded }
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Healing Tool toggle button
                        Button(
                            onClick = { 
                                if (uiState.isHealingToolActive) {
                                    viewModel.deactivateHealingTool()
                                } else {
                                    viewModel.activateHealingTool()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (uiState.isHealingToolActive) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            Icon(
                                Icons.Default.Brush,
                                contentDescription = "Healing Tool",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (uiState.isHealingToolActive) 
                                    "Exit Healing Tool" 
                                else 
                                    "Activate Healing Tool"
                            )
                        }
                        
                        // Healing tool controls (when active)
                        if (uiState.isHealingToolActive) {
                            Text(
                                text = "Paint over areas you want to heal, then tap Apply.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            // Brush size control
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Brush Size",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${uiState.healingBrushSettings.size.toInt()}px",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                
                                Slider(
                                    value = uiState.healingBrushSettings.size,
                                    onValueChange = { newSize ->
                                        viewModel.updateHealingBrushSettings(
                                            uiState.healingBrushSettings.copy(size = newSize)
                                        )
                                    },
                                    valueRange = HealingBrush.MIN_SIZE..HealingBrush.MAX_SIZE,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            
                            // Action buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Undo button
                                OutlinedButton(
                                    onClick = { viewModel.undoLastHealingStroke() },
                                    enabled = viewModel.hasHealingStrokes() && !uiState.isHealingProcessing,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Undo,
                                        contentDescription = "Undo",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Undo")
                                }
                                
                                // Clear button
                                OutlinedButton(
                                    onClick = { viewModel.clearHealingStrokes() },
                                    enabled = viewModel.hasHealingStrokes() && !uiState.isHealingProcessing,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Clear")
                                }
                            }
                            
                            // Apply healing button
                            Button(
                                onClick = { viewModel.applyHealing() },
                                enabled = viewModel.hasHealingStrokes() && !uiState.isHealingProcessing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (uiState.isHealingProcessing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Processing...")
                                } else {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Apply Healing",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Apply Healing")
                                }
                            }
                            
                            // Validation message
                            uiState.healingValidation?.let { validation ->
                                if (!validation.isValid) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer
                                        )
                                    ) {
                                        Text(
                                            text = validation.errorMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                } else if (validation.isWarning) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Text(
                                            text = validation.errorMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Landscape Enhancement section
                LandscapeEnhancementCard(
                    landscapeAnalysis = uiState.landscapeAnalysis,
                    parameters = uiState.landscapeEnhancementParameters,
                    isProcessing = uiState.isProcessing,
                    isApplied = uiState.landscapeEnhancementApplied,
                    isShowingBeforeAfter = uiState.isShowingBeforeAfter,
                    onApplyEnhancement = { viewModel.toggleLandscapeEnhancement() },
                    onParametersChange = { viewModel.updateLandscapeEnhancementParameters(it) },
                    onToggleBeforeAfter = { viewModel.toggleBeforeAfterComparison() },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Presets section
                var presetsExpanded by remember { mutableStateOf(false) }
                EditorCategoryCard(
                    title = "Presets",
                    isExpanded = presetsExpanded,
                    onToggle = { presetsExpanded = !presetsExpanded }
                ) {
                    Column {
                        // Save as Preset button
                        OutlinedButton(
                            onClick = {
                                presetNameInput = ""
                                showSavePresetDialog = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Save as Preset",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save as Preset")
                        }
                        
                        // Preset chips with long-press menu
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .heightIn(max = 60.dp)
                                .padding(vertical = 8.dp)
                        ) {
                            items(uiState.availablePresets) { preset ->
                                var showPresetMenu by remember { mutableStateOf(false) }
                                
                                Box {
                                    FilterChip(
                                        onClick = { viewModel.applyPreset(preset) },
                                        label = { Text(preset.name) },
                                        selected = uiState.selectedPreset?.id == preset.id,
                                        modifier = Modifier.combinedClickable(
                                            onClick = { viewModel.applyPreset(preset) },
                                            onLongClick = {
                                                if (!preset.isBuiltIn) {
                                                    selectedPresetForAction = preset
                                                    showPresetMenu = true
                                                }
                                            }
                                        )
                                    )
                                    
                                    // Context menu for custom presets
                                    if (!preset.isBuiltIn) {
                                        DropdownMenu(
                                            expanded = showPresetMenu,
                                            onDismissRequest = { showPresetMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Edit Name") },
                                                onClick = {
                                                    showPresetMenu = false
                                                    selectedPresetForAction = preset
                                                    presetNameInput = preset.name
                                                    showEditPresetDialog = true
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Edit, contentDescription = null)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Duplicate") },
                                                onClick = {
                                                    showPresetMenu = false
                                                    selectedPresetForAction = preset
                                                    presetNameInput = "${preset.name} Copy"
                                                    showDuplicatePresetDialog = true
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete") },
                                                onClick = {
                                                    showPresetMenu = false
                                                    selectedPresetForAction = preset
                                                    showDeletePresetDialog = true
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Crop section
                var cropExpanded by remember { mutableStateOf(false) }
                EditorCategoryCard(
                    title = "Crop",
                    isExpanded = cropExpanded,
                    onToggle = { cropExpanded = !cropExpanded }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!uiState.cropState.isActive) {
                            OutlinedButton(
                                onClick = {
                                    if (imageSize != Size.Zero && uiState.processedBitmap != null) {
                                        val containerW = imageSize.width
                                        val containerH = imageSize.height
                                        val bmpW = uiState.processedBitmap!!.width.toFloat()
                                        val bmpH = uiState.processedBitmap!!.height.toFloat()
                                        val imageAspect = if (bmpH != 0f) bmpW / bmpH else 1f
                                        val containerAspect = if (containerH != 0f) containerW / containerH else imageAspect

                                        val contentRect: Rect = if (containerW > 0f && containerH > 0f) {
                                            if (imageAspect > containerAspect) {
                                                val fitWidth = containerW
                                                val fitHeight = fitWidth / imageAspect
                                                val offsetX = 0f
                                                val offsetY = (containerH - fitHeight) / 2f
                                                Rect(offset = Offset(offsetX, offsetY), size = Size(fitWidth, fitHeight))
                                            } else {
                                                val fitHeight = containerH
                                                val fitWidth = fitHeight * imageAspect
                                                val offsetY = 0f
                                                val offsetX = (containerW - fitWidth) / 2f
                                                Rect(offset = Offset(offsetX, offsetY), size = Size(fitWidth, fitHeight))
                                            }
                                        } else {
                                            Rect(Offset.Zero, imageSize)
                                        }
                                        viewModel.enterCropMode(contentRect)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = imageSize != Size.Zero
                            ) {
                                Icon(
                                    Icons.Default.Crop,
                                    contentDescription = "Start Crop",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start Crop")
                            }
                        } else {
                            // Aspect ratio selection
                            Text("Aspect Ratio", style = MaterialTheme.typography.labelMedium)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                items(CropAspectRatio.values().toList()) { ratio ->
                                    FilterChip(
                                        selected = uiState.cropState.aspectRatio == ratio,
                                        onClick = { viewModel.setCropAspectRatio(ratio) },
                                        label = { Text(ratio.label) }
                                    )
                                }
                            }
                            
                            // Crop action buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.exitCropMode() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Cancel",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Cancel")
                                }
                                
                                Button(
                                    onClick = { viewModel.applyCrop() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Apply",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Apply")
                                }
                            }
                        }
                    }
                }
                
                // Transform section (Rotate & Flip)
                var transformExpanded by remember { mutableStateOf(false) }
                EditorCategoryCard(
                    title = "Transform",
                    isExpanded = transformExpanded,
                    onToggle = { transformExpanded = !transformExpanded }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Rotation buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.rotateLeft() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.RotateLeft,
                                    contentDescription = "Rotate Left",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Rotate Left")
                            }
                            
                            OutlinedButton(
                                onClick = { viewModel.rotateRight() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.RotateRight,
                                    contentDescription = "Rotate Right",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Rotate Right")
                            }
                        }
                        
                        // Flip buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.flipHorizontally() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.FlipToFront,
                                    contentDescription = "Flip Horizontal",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Flip Horizontal")
                            }
                            
                            OutlinedButton(
                                onClick = { viewModel.flipVertically() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Flip,
                                    contentDescription = "Flip Vertical",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Flip Vertical")
                            }
                        }
                    }
                }
                
                // Basic Adjustments section
                var basicAdjustmentsExpanded by remember { mutableStateOf(false) }
                EditorCategoryCard(
                    title = "Basic Adjustments",
                    isExpanded = basicAdjustmentsExpanded,
                    onToggle = { basicAdjustmentsExpanded = !basicAdjustmentsExpanded }
                ) {
                    
                    // Brightness
                    AdjustmentSlider(
                        label = "Brightness",
                        value = uiState.adjustments.brightness,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(brightness = value)
                            )
                        }
                    )
                    
                    // Contrast
                    AdjustmentSlider(
                        label = "Contrast",
                        value = uiState.adjustments.contrast,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(contrast = value)
                            )
                        }
                    )
                    
                    // Saturation
                    AdjustmentSlider(
                        label = "Saturation",
                        value = uiState.adjustments.saturation,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(saturation = value)
                            )
                        }
                    )
                    
                    // Warmth
                    AdjustmentSlider(
                        label = "Warmth",
                        value = uiState.adjustments.warmth,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(warmth = value)
                            )
                        }
                    )
                    
                    // Tint
                    AdjustmentSlider(
                        label = "Tint",
                        value = uiState.adjustments.tint,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(tint = value)
                            )
                        }
                    )
                }
                
                // Advanced Adjustments section
                var advancedAdjustmentsExpanded by remember { mutableStateOf(false) }
                EditorCategoryCard(
                    title = "Advanced Adjustments",
                    isExpanded = advancedAdjustmentsExpanded,
                    onToggle = { advancedAdjustmentsExpanded = !advancedAdjustmentsExpanded }
                ) {
                    
                    // Exposure
                    AdjustmentSlider(
                        label = "Exposure",
                        value = uiState.adjustments.exposure,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(exposure = value)
                            )
                        }
                    )
                    
                    // Highlights
                    AdjustmentSlider(
                        label = "Highlights",
                        value = uiState.adjustments.highlights,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(highlights = value)
                            )
                        }
                    )
                    
                    // Shadows
                    AdjustmentSlider(
                        label = "Shadows",
                        value = uiState.adjustments.shadows,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(shadows = value)
                            )
                        }
                    )
                    
                    // Whites
                    AdjustmentSlider(
                        label = "Whites",
                        value = uiState.adjustments.whites,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(whites = value)
                            )
                        }
                    )
                    
                    // Blacks
                    AdjustmentSlider(
                        label = "Blacks",
                        value = uiState.adjustments.blacks,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(blacks = value)
                            )
                        }
                    )
                    
                    // Clarity
                    AdjustmentSlider(
                        label = "Clarity",
                        value = uiState.adjustments.clarity,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(clarity = value)
                            )
                        }
                    )
                    
                    // Vibrance
                    AdjustmentSlider(
                        label = "Vibrance",
                        value = uiState.adjustments.vibrance,
                        onValueChange = { value ->
                            viewModel.updateAdjustments(
                                uiState.adjustments.copy(vibrance = value)
                            )
                        }
                    )
                }
                
                // Film Grain section
                var filmGrainExpanded by remember { mutableStateOf(false) }
                EditorCategoryCard(
                    title = "Film Grain",
                    isExpanded = filmGrainExpanded,
                    onToggle = { filmGrainExpanded = !filmGrainExpanded }
                ) {
                    
                    AdjustmentSlider(
                        label = "Grain Amount",
                        value = uiState.filmGrain.amount,
                        onValueChange = { value ->
                            viewModel.updateFilmGrain(
                                uiState.filmGrain.copy(amount = value)
                            )
                        }
                    )
                    
                    AdjustmentSlider(
                        label = "Grain Size",
                        value = uiState.filmGrain.size,
                        valueRange = 0.1f..3f,
                        onValueChange = { value ->
                            viewModel.updateFilmGrain(
                                uiState.filmGrain.copy(size = value)
                            )
                        }
                    )
                    
                    AdjustmentSlider(
                        label = "Grain Roughness",
                        value = uiState.filmGrain.roughness,
                        valueRange = 0f..1f,
                        onValueChange = { value ->
                            viewModel.updateFilmGrain(
                                uiState.filmGrain.copy(roughness = value)
                            )
                        }
                    )
                }
                
                // Lens Effects section
                var lensEffectsExpanded by remember { mutableStateOf(false) }
                EditorCategoryCard(
                    title = "Lens Effects",
                    isExpanded = lensEffectsExpanded,
                    onToggle = { lensEffectsExpanded = !lensEffectsExpanded }
                ) {
                    
                    AdjustmentSlider(
                        label = "Vignette Amount",
                        value = uiState.lensEffects.vignetteAmount,
                        onValueChange = { value ->
                            viewModel.updateLensEffects(
                                uiState.lensEffects.copy(vignetteAmount = value)
                            )
                        }
                    )
                    
                    AdjustmentSlider(
                        label = "Vignette Midpoint",
                        value = uiState.lensEffects.vignetteMidpoint,
                        valueRange = 0f..1f,
                        onValueChange = { value ->
                            viewModel.updateLensEffects(
                                uiState.lensEffects.copy(vignetteMidpoint = value)
                            )
                        }
                    )
                    
                    AdjustmentSlider(
                        label = "Vignette Feather",
                        value = uiState.lensEffects.vignetteFeather,
                        valueRange = 0f..1f,
                        onValueChange = { value ->
                            viewModel.updateLensEffects(
                                uiState.lensEffects.copy(vignetteFeather = value)
                            )
                        }
                    )
                    
                    AdjustmentSlider(
                        label = "Chromatic Aberration",
                        value = uiState.lensEffects.chromaticAberration,
                        onValueChange = { value ->
                            viewModel.updateLensEffects(
                                uiState.lensEffects.copy(chromaticAberration = value)
                            )
                        }
                    )
                    
                    AdjustmentSlider(
                        label = "Lens Distortion",
                        value = uiState.lensEffects.distortion,
                        onValueChange = { value ->
                            viewModel.updateLensEffects(
                                uiState.lensEffects.copy(distortion = value)
                            )
                        }
                    )
                }
            }
        }
        
        // Smart Enhancement Error Dialog
        SmartEnhancementErrorDialog(
            error = uiState.error,
            operation = "enhance photo",
            onDismiss = { viewModel.clearError() },
            onRetry = { 
                // Retry the last operation based on what was being processed
                when {
                    uiState.smartEnhancementApplied -> viewModel.applySmartEnhancement()
                    uiState.portraitEnhancementApplied -> viewModel.togglePortraitEnhancement()
                    else -> viewModel.applySmartEnhancement()
                }
            },
            onFallbackToManual = {
                // Clear error and let user use manual controls
                viewModel.clearError()
            }
        )
        
        // Inline Progress Indicator for Smart Enhancement
        if (uiState.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                SmartEnhancementInlineProgress(
                    isVisible = true,
                    operation = when {
                        uiState.isPortraitEnhancementActive -> "Enhancing portrait..."
                        else -> "Applying smart enhancement..."
                    },
                    progress = null, // Indeterminate for now
                    modifier = Modifier.padding(bottom = 80.dp) // Above bottom navigation
                )
            }
        }
    }
    
    // Error message
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Show snackbar or handle error
        }
    }
    
    // Unsaved changes dialog
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes. Do you want to discard them?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Dialog components
    SavePresetDialog(
        showDialog = showSavePresetDialog,
        presetName = presetNameInput,
        onPresetNameChange = { presetNameInput = it },
        onDismiss = { showSavePresetDialog = false },
        onSave = { name ->
            viewModel.saveCurrentAsPreset(name)
            showSavePresetDialog = false
        }
    )
    
    EditPresetDialog(
        showDialog = showEditPresetDialog,
        presetName = presetNameInput,
        onPresetNameChange = { presetNameInput = it },
        onDismiss = { showEditPresetDialog = false },
        onUpdate = { name ->
            selectedPresetForAction?.let { preset ->
                viewModel.updatePreset(preset.id, name)
            }
            showEditPresetDialog = false
        }
    )
    
    DuplicatePresetDialog(
        showDialog = showDuplicatePresetDialog,
        presetName = presetNameInput,
        onPresetNameChange = { presetNameInput = it },
        onDismiss = { showDuplicatePresetDialog = false },
        onDuplicate = { name ->
            selectedPresetForAction?.let { preset ->
                viewModel.duplicatePreset(preset, name)
            }
            showDuplicatePresetDialog = false
        }
    )
    
    DeletePresetDialog(
        showDialog = showDeletePresetDialog,
        presetName = selectedPresetForAction?.name ?: "",
        onDismiss = { showDeletePresetDialog = false },
        onDelete = {
            selectedPresetForAction?.let { preset ->
                viewModel.deletePreset(preset.id)
            }
            showDeletePresetDialog = false
        }
    )
    
    // Error dialog with retry functionality
    ErrorDialog(
        error = uiState.error,
        onDismiss = { viewModel.clearError() },
        onRetry = { viewModel.retryLoadPhoto() }
    )
    
    // Smart Enhancement Error Dialog
    uiState.error?.let { error ->
        SmartEnhancementErrorDialog(
            error = error,
            operation = "enhance photo",
            onDismiss = { viewModel.clearError() },
            onRetry = { 
                // Retry the last operation based on what was being processed
                when {
                    uiState.smartEnhancementApplied -> viewModel.applySmartEnhancement()
                    uiState.portraitEnhancementApplied -> viewModel.togglePortraitEnhancement()
                    else -> viewModel.applySmartEnhancement()
                }
            },
            onFallbackToManual = {
                // Clear error and let user use manual controls
                viewModel.clearError()
            }
        )
    }
    
    // NOTE: Duplicate progress indicator removed - only one instance at line 1254-1271 is kept
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${(value * 100).toInt()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EditorCategoryCard(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand"
                )
            }
            
            // Content
            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}

// Save as Preset Dialog
@Composable
private fun SavePresetDialog(
    showDialog: Boolean,
    presetName: String,
    onPresetNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Save as Preset") },
            text = {
                Column {
                    Text(
                        "Enter a name for your custom preset:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = onPresetNameChange,
                        label = { Text("Preset Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetName.isNotBlank()) {
                            onSave(presetName.trim())
                        }
                    },
                    enabled = presetName.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Edit Preset Dialog
@Composable
private fun EditPresetDialog(
    showDialog: Boolean,
    presetName: String,
    onPresetNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onUpdate: (String) -> Unit
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Edit Preset Name") },
            text = {
                Column {
                    Text(
                        "Enter a new name for this preset:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = onPresetNameChange,
                        label = { Text("Preset Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetName.isNotBlank()) {
                            onUpdate(presetName.trim())
                        }
                    },
                    enabled = presetName.isNotBlank()
                ) {
                    Text("Update")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Duplicate Preset Dialog
@Composable
private fun DuplicatePresetDialog(
    showDialog: Boolean,
    presetName: String,
    onPresetNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onDuplicate: (String) -> Unit
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Duplicate Preset") },
            text = {
                Column {
                    Text(
                        "Enter a name for the duplicated preset:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = onPresetNameChange,
                        label = { Text("Preset Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetName.isNotBlank()) {
                            onDuplicate(presetName.trim())
                        }
                    },
                    enabled = presetName.isNotBlank()
                ) {
                    Text("Duplicate")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Delete Preset Confirmation Dialog
@Composable
private fun DeletePresetDialog(
    showDialog: Boolean,
    presetName: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete Preset") },
            text = {
                Text(
                    "Are you sure you want to delete \"$presetName\"? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}
