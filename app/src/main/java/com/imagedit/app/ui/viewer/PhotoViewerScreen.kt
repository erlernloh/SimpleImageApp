package com.imagedit.app.ui.viewer

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
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
 * Zoomable image composable using SubsamplingScaleImageView for full resolution viewing.
 * Uses tile-based loading to handle very large images (e.g., 71MP ULTRA output) without OOM.
 * Supports pinch-to-zoom and double-tap to zoom.
 */
@Composable
private fun ZoomableImage(
    photoUri: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentScale by remember { mutableFloatStateOf(1f) }
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        // Use SubsamplingScaleImageView for efficient large image viewing
        // It loads tiles on-demand, allowing full resolution zoom without OOM
        AndroidView(
            factory = { ctx ->
                SubsamplingScaleImageView(ctx).apply {
                    // Configure for optimal viewing
                    setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                    setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER_IMMEDIATE)
                    setDoubleTapZoomDuration(300)
                    setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                    
                    // Set max scale to allow pixel-level zoom (will be adjusted based on image size)
                    maxScale = 10f
                    
                    // Enable quick scale (pinch zoom)
                    isQuickScaleEnabled = true
                    
                    // Set double tap zoom scales
                    setDoubleTapZoomScale(3f)
                    
                    // Listen for scale changes to update the zoom indicator
                    setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
                        override fun onScaleChanged(newScale: Float, origin: Int) {
                            currentScale = newScale
                        }
                        override fun onCenterChanged(newCenter: android.graphics.PointF?, origin: Int) {}
                    })
                    
                    // Load the image
                    setImage(ImageSource.uri(photoUri))
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // Update image if URI changes
                view.setImage(ImageSource.uri(photoUri))
            }
        )
        
        // Zoom indicator (shows when zoomed)
        if (currentScale > 1.1f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            ) {
                Text(
                    text = "${String.format("%.1f", currentScale)}x",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
