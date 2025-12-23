/**
 * ModelManagementScreen.kt - UI for downloading and managing AI models
 */

package com.imagedit.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedit.app.ultradetail.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Model management UI state
 */
data class ModelManagementUiState(
    val models: List<ModelItemState> = emptyList(),
    val totalDownloadedSize: Long = 0
)

data class ModelItemState(
    val modelInfo: ModelInfo,
    val isDownloaded: Boolean = false,
    val downloadState: DownloadState = DownloadState.Idle,
    val localSizeBytes: Long = 0
)

/**
 * ViewModel for model management
 */
class ModelManagementViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow(ModelManagementUiState())
    val uiState: StateFlow<ModelManagementUiState> = _uiState.asStateFlow()
    
    private var modelDownloader: ModelDownloader? = null
    
    fun initialize(context: android.content.Context) {
        modelDownloader = ModelDownloader(context)
        refreshModelList()
    }
    
    private fun refreshModelList() {
        val downloader = modelDownloader ?: return
        
        val allModels = listOf(
            AvailableModels.REAL_ESRGAN_X4_FP16,
            AvailableModels.REAL_ESRGAN_X4_ANIME_FP16,
            AvailableModels.SWINIR_X4_FP16,
            AvailableModels.ESRGAN_FP16,
            AvailableModels.ESRGAN_INT8
        )
        
        val modelStates = allModels.map { model ->
            val isDownloaded = downloader.isModelAvailable(model)
            val localPath = downloader.getModelPath(model)
            val localSize = if (localPath != null) {
                java.io.File(localPath).length()
            } else {
                0L
            }
            
            ModelItemState(
                modelInfo = model,
                isDownloaded = isDownloaded,
                localSizeBytes = localSize
            )
        }
        
        val totalSize = modelStates.filter { it.isDownloaded }.sumOf { it.localSizeBytes }
        
        _uiState.value = ModelManagementUiState(
            models = modelStates,
            totalDownloadedSize = totalSize
        )
    }
    
    fun downloadModel(model: ModelInfo) {
        val downloader = modelDownloader ?: return
        
        viewModelScope.launch {
            downloader.downloadModel(model).collect { state ->
                updateModelDownloadState(model, state)
                
                if (state is DownloadState.Complete || state is DownloadState.Error) {
                    refreshModelList()
                }
            }
        }
    }
    
    private fun updateModelDownloadState(model: ModelInfo, state: DownloadState) {
        val currentModels = _uiState.value.models.toMutableList()
        val index = currentModels.indexOfFirst { it.modelInfo.fileName == model.fileName }
        
        if (index >= 0) {
            currentModels[index] = currentModels[index].copy(downloadState = state)
            _uiState.value = _uiState.value.copy(models = currentModels)
        }
    }
    
    fun deleteModel(model: ModelInfo) {
        val downloader = modelDownloader ?: return
        
        viewModelScope.launch {
            downloader.deleteModel(model)
            refreshModelList()
        }
    }
}

/**
 * Model management screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: ModelManagementViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Model Management") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Storage info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Storage Usage",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val totalMB = uiState.totalDownloadedSize / (1024f * 1024f)
                    val downloadedCount = uiState.models.count { it.isDownloaded }
                    
                    Text(
                        text = "Downloaded: $downloadedCount models (${String.format("%.1f", totalMB)} MB)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            // Model list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.models) { modelState ->
                    ModelCard(
                        modelState = modelState,
                        onDownload = { viewModel.downloadModel(modelState.modelInfo) },
                        onDelete = { viewModel.deleteModel(modelState.modelInfo) }
                    )
                }
            }
        }
    }
}

@Composable
fun ModelCard(
    modelState: ModelItemState,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Model name and runtime badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelState.modelInfo.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Runtime badge
                    Surface(
                        color = if (modelState.modelInfo.runtime == ModelRuntime.ONNX) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = modelState.modelInfo.runtime.name,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                
                // Status icon
                Icon(
                    imageVector = if (modelState.isDownloaded) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.CloudDownload
                    },
                    contentDescription = null,
                    tint = if (modelState.isDownloaded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Description
            Text(
                text = modelState.modelInfo.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Size info
            val sizeMB = if (modelState.isDownloaded) {
                modelState.localSizeBytes / (1024f * 1024f)
            } else {
                modelState.modelInfo.expectedSizeBytes / (1024f * 1024f)
            }
            
            Text(
                text = if (modelState.isDownloaded) {
                    "Downloaded: ${String.format("%.1f", sizeMB)} MB"
                } else {
                    "Size: ${String.format("%.1f", sizeMB)} MB"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Download progress or action buttons
            when (val state = modelState.downloadState) {
                is DownloadState.Downloading -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column {
                        LinearProgressIndicator(
                            progress = state.progress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${String.format("%.1f", state.downloadedMB)} / ${String.format("%.1f", state.totalMB)} MB",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                is DownloadState.Extracting -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Extracting...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                is DownloadState.Error -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Error: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    // Action buttons
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (modelState.isDownloaded) {
                            OutlinedButton(
                                onClick = { showDeleteDialog = true }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete")
                            }
                        } else {
                            Button(onClick = onDownload) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Model?") },
            text = { Text("Are you sure you want to delete ${modelState.modelInfo.name}? You can download it again later.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
