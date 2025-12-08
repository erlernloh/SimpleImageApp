package com.imagedit.app.ui.gallery

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.imagedit.app.domain.model.Photo
import com.imagedit.app.ui.gallery.SortOption
import com.imagedit.app.ui.common.ExportFormat
import com.imagedit.app.ui.common.ExportQuality
import com.imagedit.app.ui.common.ExportOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onNavigateBack: () -> Unit,
    onPhotoSelected: (String) -> Unit,
    onPhotoView: (String) -> Unit = {},
    onBatchEdit: (List<String>) -> Unit = {},
    exportPhotoUri: String? = null,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // Load photos when screen becomes visible (lazy loading)
    LaunchedEffect(Unit) {
        viewModel.onScreenVisible()
    }
    
    // Trigger export dialog if navigated from viewer with export intent
    LaunchedEffect(exportPhotoUri) {
        exportPhotoUri?.let { uri ->
            val photo = uiState.photos.find { it.uri.toString() == uri }
            photo?.let { viewModel.showExportDialog(it) }
        }
    }
    
    // Memoize photos to show to prevent unnecessary recompositions
    val photosToShow = remember(uiState.showFavoritesOnly, uiState.photos, uiState.favoritePhotos) {
        if (uiState.showFavoritesOnly) uiState.favoritePhotos else uiState.photos
    }
    
    // File picker launcher for selecting images from external storage
    val filePickerLauncher = rememberLauncherForActivityResult<String, Uri?>(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Navigate to editor with selected file
            onPhotoSelected(it.toString())
        }
    }
    
    // Export file picker launcher
    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/*")
    ) { uri: Uri? ->
        uri?.let { destinationUri ->
            viewModel.exportPhoto(destinationUri) { result ->
                result.fold(
                    onSuccess = {
                        // Show success message
                    },
                    onFailure = { error ->
                        // Error already set in viewModel
                    }
                )
            }
        }
    }
    
    // Show error snackbar
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            // Error will be shown in UI
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gallery", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (uiState.selectionMode) {
                        // Selection mode actions
                        Text(
                            text = "${uiState.selectedPhotos.size} selected",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        
                        // Bulk operations menu
                        var showBulkMenu by remember { mutableStateOf(false) }
                        
                        Box {
                            IconButton(
                                onClick = { showBulkMenu = true },
                                enabled = uiState.selectedPhotos.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Bulk Actions",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            DropdownMenu(
                                expanded = showBulkMenu,
                                onDismissRequest = { showBulkMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Add to Favorites") },
                                    onClick = {
                                        viewModel.favoriteSelectedPhotos()
                                        showBulkMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Favorite, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove from Favorites") },
                                    onClick = {
                                        viewModel.unfavoriteSelectedPhotos()
                                        showBulkMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.FavoriteBorder, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    onClick = {
                                        viewModel.shareSelectedPhotos { uris ->
                                            // Share intent will be handled here
                                        }
                                        showBulkMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                    }
                                )
                            }
                        }
                        
                        // Edit Selected button - batch edit multiple photos
                        IconButton(
                            onClick = {
                                val selectedUris = photosToShow
                                    .filter { it.id in uiState.selectedPhotos }
                                    .map { it.uri.toString() }
                                if (selectedUris.isNotEmpty()) {
                                    viewModel.toggleSelectionMode() // Exit selection mode
                                    onBatchEdit(selectedUris)
                                }
                            },
                            enabled = uiState.selectedPhotos.size >= 1
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit Selected",
                                tint = if (uiState.selectedPhotos.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        IconButton(
                            onClick = { viewModel.selectAllPhotos() },
                            enabled = uiState.selectedPhotos.size != photosToShow.size
                        ) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = "Select All",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        IconButton(
                            onClick = { 
                                if (uiState.selectedPhotos.isNotEmpty()) {
                                    viewModel.deleteSelectedPhotos()
                                }
                            },
                            enabled = uiState.selectedPhotos.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = if (uiState.selectedPhotos.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        IconButton(
                            onClick = { viewModel.toggleSelectionMode() }
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Exit Selection Mode",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        // Normal mode actions
                        var showSortMenu by remember { mutableStateOf(false) }
                        
                        // Sort button with dropdown
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    Icons.Default.Sort,
                                    contentDescription = "Sort",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Date Taken (Newest)") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.DATE_TAKEN_DESC)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (uiState.sortOption == SortOption.DATE_TAKEN_DESC) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Date Taken (Oldest)") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.DATE_TAKEN_ASC)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (uiState.sortOption == SortOption.DATE_TAKEN_ASC) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("File Size (Largest)") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.FILE_SIZE_DESC)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (uiState.sortOption == SortOption.FILE_SIZE_DESC) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("File Size (Smallest)") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.FILE_SIZE_ASC)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (uiState.sortOption == SortOption.FILE_SIZE_ASC) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Name (A-Z)") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.NAME_ASC)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (uiState.sortOption == SortOption.NAME_ASC) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Name (Z-A)") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.NAME_DESC)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (uiState.sortOption == SortOption.NAME_DESC) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Favorites First") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.FAVORITES_FIRST)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (uiState.sortOption == SortOption.FAVORITES_FIRST) {
                                            Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    }
                                )
                            }
                        }
                        
                        IconButton(
                            onClick = { viewModel.toggleSelectionMode() },
                            enabled = photosToShow.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Select",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        IconButton(
                            onClick = { filePickerLauncher.launch("image/*") }
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = "Browse",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        IconButton(
                            onClick = { viewModel.toggleShowFavoritesOnly() }
                        ) {
                            Icon(
                                Icons.Default.FilterList,
                                contentDescription = "Filter",
                                tint = if (uiState.showFavoritesOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        IconButton(
                            onClick = { viewModel.refreshPhotos() }
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
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
            when {
                photosToShow.isEmpty() && uiState.isLoading -> {
                    // Initial loading state (only show when no photos loaded yet)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Loading photos...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                photosToShow.isEmpty() && !uiState.isLoading -> {
                    // Empty state with file picker option
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = if (uiState.showFavoritesOnly) "No favorite photos" else "No photos found",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = if (uiState.showFavoritesOnly) {
                                    "Mark some photos as favorites to see them here"
                                } else {
                                    "Take photos or browse files from your device"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            // Browse files button
                            Button(
                                onClick = { filePickerLauncher.launch("image/*") },
                                modifier = Modifier.fillMaxWidth(0.6f)
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Browse Files")
                            }
                            
                            // Refresh button
                            FilledTonalButton(
                                onClick = { viewModel.refreshPhotos() },
                                modifier = Modifier.fillMaxWidth(0.6f)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Refresh Gallery")
                            }
                        }
                    }
                }
                
                else -> {
                    // Photo grid
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            items = photosToShow,
                            key = { index, photo -> "${photo.id}_$index" }
                        ) { index, photo ->
                            val isSelected = remember(uiState.selectedPhotos, photo.id) {
                                photo.id in uiState.selectedPhotos
                            }
                            
                            // Load next page when near the end
                            if (index >= photosToShow.size - 6 && uiState.hasMorePages && !uiState.isLoading) {
                                LaunchedEffect(Unit) {
                                    viewModel.loadNextPage()
                                }
                            }
                            
                            PhotoGridItem(
                                photo = photo,
                                isSelected = isSelected,
                                selectionMode = uiState.selectionMode,
                                onPhotoClick = {
                                    if (uiState.selectionMode) {
                                        viewModel.togglePhotoSelection(photo.id)
                                    } else {
                                        viewModel.selectPhoto(photo)
                                        onPhotoView(photo.uri.toString())
                                    }
                                }
                            )
                        }
                        
                        // Loading indicator at bottom when loading more pages
                        if (uiState.isLoading && photosToShow.isNotEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
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
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.clearError() }
                        ) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }
    
    // Export Dialog
    if (uiState.showExportDialog) {
        ExportDialog(
            photoName = uiState.photoToExport?.name ?: "photo",
            exportOptions = uiState.exportOptions,
            onDismiss = { viewModel.hideExportDialog() },
            onExportOptionsChange = { viewModel.updateExportOptions(it) },
            onExport = { filename ->
                exportFileLauncher.launch(filename)
            }
        )
    }
}

@Composable
private fun ExportDialog(
    photoName: String,
    exportOptions: ExportOptions,
    onDismiss: () -> Unit,
    onExportOptionsChange: (ExportOptions) -> Unit,
    onExport: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Photo") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Format", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportFormat.values().forEach { format ->
                        FilterChip(
                            selected = exportOptions.format == format,
                            onClick = { onExportOptionsChange(exportOptions.copy(format = format)) },
                            label = { 
                                Text(
                                    text = format.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                ) 
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                Text("Quality", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ExportQuality.values().forEach { quality ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onExportOptionsChange(exportOptions.copy(quality = quality)) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = exportOptions.quality == quality,
                                onClick = { onExportOptionsChange(exportOptions.copy(quality = quality)) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (quality) {
                                    ExportQuality.LOW -> "Low (60%)"
                                    ExportQuality.MEDIUM -> "Medium (80%)"
                                    ExportQuality.HIGH -> "High (95%)"
                                    ExportQuality.MAXIMUM -> "Max (100%)"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                Text("Resize", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(100, 75, 50, 25).forEach { percentage ->
                        FilterChip(
                            selected = exportOptions.resizePercentage == percentage,
                            onClick = { onExportOptionsChange(exportOptions.copy(resizePercentage = percentage)) },
                            label = { 
                                Text(
                                    text = "$percentage%",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                ) 
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val extension = when (exportOptions.format) {
                        ExportFormat.JPEG -> "jpg"
                        ExportFormat.PNG -> "png"
                        ExportFormat.WEBP -> "webp"
                    }
                    val baseName = photoName.substringBeforeLast(".")
                    val filename = "${baseName}_exported.$extension"
                    onExport(filename)
                }
            ) {
                Text("Export")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PhotoGridItem(
    photo: com.imagedit.app.domain.model.Photo,
    isSelected: Boolean,
    selectionMode: Boolean,
    onPhotoClick: () -> Unit
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onPhotoClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Box {
            // PERFORMANCE: Optimized image loading to reduce frame drops
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photo.uri)
                    .size(200, 200) // Reduced size for faster loading
                    .crossfade(false) // Disable crossfade to prevent frame drops
                    .memoryCacheKey("${photo.id}_thumb") // Stable cache key
                    .diskCacheKey("${photo.id}_thumb") // Stable disk cache key
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .allowHardware(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                placeholder = null,
                error = null
            )
            
            // Selection checkbox overlay
            if (selectionMode) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(24.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
            
            // Favorite indicator
            if (photo.isFavorite && !selectionMode) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = "Favorite",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            // Edit indicator
            if (photo.hasEdits && !selectionMode) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = "EDITED",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            // Ultra Detail+ indicator
            if (photo.isUltraDetail && !selectionMode) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                ) {
                    Text(
                        text = if (photo.isMFSR) "UD+" else "UD",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiary
                    )
                }
            }
        }
    }
}
