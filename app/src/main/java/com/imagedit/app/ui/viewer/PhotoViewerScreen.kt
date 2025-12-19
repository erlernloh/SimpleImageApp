package com.imagedit.app.ui.viewer

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    photoUri: String,
    onNavigateBack: () -> Unit,
    onEditPhoto: (String) -> Unit,
    onExportPhoto: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var showProperties by remember { mutableStateOf(false) }
    var photoProperties by remember { mutableStateOf<PhotoProperties?>(null) }
    
    // Load photo properties
    LaunchedEffect(photoUri) {
        photoProperties = loadPhotoProperties(context.contentResolver, Uri.parse(photoUri))
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo Viewer") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Export button (if provided)
                    onExportPhoto?.let { exportCallback ->
                        IconButton(onClick = { exportCallback(photoUri) }) {
                            Icon(Icons.Default.Share, contentDescription = "Export")
                        }
                    }
                    
                    // Edit button
                    IconButton(onClick = { onEditPhoto(photoUri) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
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
            // Zoomable image view
            ZoomableImage(
                photoUri = photoUri,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            
            // Properties panel at bottom (always visible)
            if (photoProperties != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Photo Properties",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        photoProperties?.let { props ->
                            // Compact property display in rows
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    CompactPropertyItem("Name", props.name)
                                    CompactPropertyItem("Dimensions", "${props.width} × ${props.height}")
                                    CompactPropertyItem("Date Taken", props.dateTaken)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    CompactPropertyItem("Size", props.sizeFormatted)
                                    CompactPropertyItem("Type", props.mimeType)
                                    CompactPropertyItem("Modified", props.dateModified)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PropertyItem(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp)
        )
        Divider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun CompactPropertyItem(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 1
        )
    }
}

data class PhotoProperties(
    val name: String,
    val relativePath: String, // Use RELATIVE_PATH instead of deprecated DATA
    val size: Long,
    val sizeFormatted: String,
    val width: Int,
    val height: Int,
    val dateTaken: String,
    val dateModified: String,
    val mimeType: String,
    val orientation: Int
)

private fun loadPhotoProperties(contentResolver: ContentResolver, uri: Uri): PhotoProperties? {
    return try {
        // Use RELATIVE_PATH instead of deprecated DATA column (Android 10+)
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.ORIENTATION
        )
        
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val relativePathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val widthIndex = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightIndex = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateModifiedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val mimeTypeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val orientationIndex = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
                
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val relativePath = if (relativePathIndex >= 0) cursor.getString(relativePathIndex) else null
                val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                val width = if (widthIndex >= 0) cursor.getInt(widthIndex) else 0
                val height = if (heightIndex >= 0) cursor.getInt(heightIndex) else 0
                val dateTaken = if (dateTakenIndex >= 0) cursor.getLong(dateTakenIndex) else 0L
                val dateModified = if (dateModifiedIndex >= 0) cursor.getLong(dateModifiedIndex) else 0L
                val mimeType = if (mimeTypeIndex >= 0) cursor.getString(mimeTypeIndex) else null
                val orientation = if (orientationIndex >= 0) cursor.getInt(orientationIndex) else 0
                
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                val dateTakenFormatted = if (dateTaken > 0) {
                    dateFormat.format(Date(dateTaken))
                } else {
                    "Unknown"
                }
                val dateModifiedFormatted = if (dateModified > 0) {
                    dateFormat.format(Date(dateModified * 1000))
                } else {
                    "Unknown"
                }
                
                PhotoProperties(
                    name = name ?: uri.lastPathSegment ?: "Unknown",
                    relativePath = relativePath ?: "Pictures",
                    size = size,
                    sizeFormatted = formatFileSize(size),
                    width = width,
                    height = height,
                    dateTaken = dateTakenFormatted,
                    dateModified = dateModifiedFormatted,
                    mimeType = mimeType ?: "image/*",
                    orientation = orientation
                )
            } else null
        } ?: run {
            // Fallback: Try to get basic info from the URI using openFileDescriptor
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val size = pfd.statSize
                    
                    // Try to get image dimensions using BitmapFactory
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
                    }
                    
                    PhotoProperties(
                        name = uri.lastPathSegment ?: "Unknown",
                        relativePath = "Pictures",
                        size = size,
                        sizeFormatted = formatFileSize(size),
                        width = options.outWidth,
                        height = options.outHeight,
                        dateTaken = "Unknown",
                        dateModified = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                            .format(Date(System.currentTimeMillis())),
                        mimeType = options.outMimeType ?: "image/*",
                        orientation = 0
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes bytes"
    }
}

/**
 * Zoomable image composable with pinch-to-zoom and pan support.
 * Allows zooming up to see individual pixels for larger images.
 */
@Composable
private fun ZoomableImage(
    photoUri: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    
    // Calculate max zoom based on image size - larger images can zoom more
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    val minScale = 1f
    // Allow zooming up to see pixels: max zoom = max(image dimension / container dimension, 10x)
    val maxScale by remember(imageSize, containerSize) {
        derivedStateOf {
            if (containerSize.width > 0 && containerSize.height > 0 && imageSize.width > 0) {
                val scaleToPixel = maxOf(
                    imageSize.width.toFloat() / containerSize.width,
                    imageSize.height.toFloat() / containerSize.height
                )
                // Allow at least 5x zoom, up to pixel-level zoom (capped at 20x for performance)
                maxOf(5f, minOf(scaleToPixel * 2f, 20f))
            } else {
                10f
            }
        }
    }
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // Update scale with bounds
                    val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                    
                    // Calculate new offset to zoom towards the centroid
                    val scaleDiff = newScale / scale
                    val newOffset = Offset(
                        x = (offset.x * scaleDiff) + (centroid.x * (1 - scaleDiff)) + (pan.x * newScale),
                        y = (offset.y * scaleDiff) + (centroid.y * (1 - scaleDiff)) + (pan.y * newScale)
                    )
                    
                    // Constrain offset to keep image in bounds
                    val maxOffsetX = (containerSize.width * (newScale - 1) / 2).coerceAtLeast(0f)
                    val maxOffsetY = (containerSize.height * (newScale - 1) / 2).coerceAtLeast(0f)
                    
                    scale = newScale
                    offset = Offset(
                        x = newOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
                        y = newOffset.y.coerceIn(-maxOffsetY, maxOffsetY)
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        // Double tap to toggle between 1x and 3x zoom
                        if (scale > 1.5f) {
                            // Reset to 1x
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // Zoom to 3x centered on tap point
                            scale = 3f
                            val centerX = containerSize.width / 2f
                            val centerY = containerSize.height / 2f
                            offset = Offset(
                                x = (centerX - tapOffset.x) * 2f,
                                y = (centerY - tapOffset.y) * 2f
                            )
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(Uri.parse(photoUri))
                // Limit max size to prevent OOM on very large images (e.g., 71MP ULTRA output)
                // 4096x4096 is a safe limit for most devices while still allowing good zoom
                .size(4096, 4096)
                .crossfade(true)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .allowHardware(false) // Disable hardware bitmaps for large images to prevent crashes
                .listener(
                    onSuccess = { _, result ->
                        // Get actual image dimensions
                        imageSize = IntSize(
                            result.drawable.intrinsicWidth,
                            result.drawable.intrinsicHeight
                        )
                    }
                )
                .build(),
            contentDescription = "Photo (pinch to zoom, double-tap to toggle zoom)",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit
        )
        
        // Zoom indicator (shows when zoomed)
        if (scale > 1.1f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            ) {
                Text(
                    text = "${String.format("%.1f", scale)}x",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
