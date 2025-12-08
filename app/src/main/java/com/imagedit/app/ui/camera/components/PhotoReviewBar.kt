package com.imagedit.app.ui.camera.components

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.imagedit.app.domain.model.CapturedPhotoItem

/**
 * Photo Review Bar - Shows thumbnails of captured photos in current session
 * Supports both single selection and multi-selection for batch editing
 */
@Composable
fun PhotoReviewBar(
    capturedPhotos: List<CapturedPhotoItem>,
    selectedUri: String?,
    selectedUris: Set<String> = emptySet(),
    isMultiSelectMode: Boolean = false,
    onPhotoSelected: (Uri) -> Unit,
    onPhotoMultiSelected: (Uri) -> Unit = {},
    onEditSelected: () -> Unit,
    onBatchEditSelected: (List<String>) -> Unit = {},
    onToggleMultiSelect: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Auto-scroll to latest photo when new photo is added
    LaunchedEffect(capturedPhotos.size) {
        if (capturedPhotos.isNotEmpty()) {
            listState.animateScrollToItem(capturedPhotos.size - 1)
        }
    }
    
    AnimatedVisibility(
        visible = capturedPhotos.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                // Header with photo count and multi-select toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isMultiSelectMode && selectedUris.isNotEmpty()) {
                            "${selectedUris.size} of ${capturedPhotos.size} selected"
                        } else {
                            "${capturedPhotos.size} photo${if (capturedPhotos.size > 1) "s" else ""} captured"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Multi-select toggle (only show when 2+ photos)
                        if (capturedPhotos.size >= 2) {
                            if (isMultiSelectMode) {
                                // Select All button
                                IconButton(
                                    onClick = onSelectAll,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.SelectAll,
                                        contentDescription = "Select All",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            
                            // Toggle multi-select mode
                            FilterChip(
                                selected = isMultiSelectMode,
                                onClick = onToggleMultiSelect,
                                label = { 
                                    Text(
                                        if (isMultiSelectMode) "Done" else "Multi",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                leadingIcon = if (isMultiSelectMode) {
                                    {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                modifier = Modifier.height(28.dp)
                            )
                        }
                        
                        // Hint text
                        if (!isMultiSelectMode && selectedUri != null) {
                            Text(
                                text = "Tap Edit",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Horizontal scrollable row of thumbnails
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(capturedPhotos, key = { it.uri }) { photo ->
                        ThumbnailItem(
                            photo = photo,
                            isSelected = if (isMultiSelectMode) {
                                photo.uri in selectedUris
                            } else {
                                photo.uri == selectedUri
                            },
                            isMultiSelectMode = isMultiSelectMode,
                            onClick = {
                                if (isMultiSelectMode) {
                                    onPhotoMultiSelected(photo.getUri())
                                } else {
                                    onPhotoSelected(photo.getUri())
                                }
                            }
                        )
                    }
                }
                
                // Action buttons based on mode
                AnimatedVisibility(
                    visible = if (isMultiSelectMode) selectedUris.isNotEmpty() else selectedUri != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    if (isMultiSelectMode && selectedUris.size >= 2) {
                        // Batch Edit button for multi-selection
                        Button(
                            onClick = { onBatchEditSelected(selectedUris.toList()) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Edit ${selectedUris.size} Photos Together")
                        }
                    } else if (isMultiSelectMode && selectedUris.size == 1) {
                        // Single photo selected in multi-select mode
                        Button(
                            onClick = onEditSelected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Edit Selected Photo")
                        }
                    } else if (!isMultiSelectMode && selectedUri != null) {
                        // Single selection mode
                        Button(
                            onClick = onEditSelected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Edit Selected Photo")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual thumbnail item in the review bar
 */
@Composable
private fun ThumbnailItem(
    photo: CapturedPhotoItem,
    isSelected: Boolean,
    isMultiSelectMode: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
    ) {
        // Thumbnail image - PERFORMANCE: Use thumbnail if available, fallback to original with small size
        AsyncImage(
            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(photo.getThumbnailUri() ?: photo.getUri())
                .size(120, 120) // Reduced size for faster loading
                .crossfade(false) // Disable crossfade for faster display
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = null,
            modifier = Modifier.fillMaxSize()
        )
        
        // Selection indicator overlay
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
            )
            
            // Checkmark icon
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(2.dp)
            )
        }
    }
}
