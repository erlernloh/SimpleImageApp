package com.imagedit.app.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedit.app.domain.model.Photo
import com.imagedit.app.domain.repository.PhotoRepository
import com.imagedit.app.ui.common.ExportFormat
import com.imagedit.app.ui.common.ExportQuality
import com.imagedit.app.ui.common.ExportOptions
import com.imagedit.app.util.image.ImageUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

enum class SortOption {
    DATE_TAKEN_DESC,
    DATE_TAKEN_ASC,
    DATE_MODIFIED_DESC,
    DATE_MODIFIED_ASC,
    FILE_SIZE_DESC,
    FILE_SIZE_ASC,
    NAME_ASC,
    NAME_DESC,
    FAVORITES_FIRST
}

data class GalleryUiState(
    val photos: List<Photo> = emptyList(),
    val favoritePhotos: List<Photo> = emptyList(),
    val isLoading: Boolean = false,
    val hasMorePages: Boolean = true,
    val error: String? = null,
    val selectedPhoto: Photo? = null,
    val showFavoritesOnly: Boolean = false,
    val selectionMode: Boolean = false,
    val selectedPhotos: Set<String> = emptySet(),
    val sortOption: SortOption = SortOption.DATE_TAKEN_DESC,
    val showExportDialog: Boolean = false,
    val exportOptions: ExportOptions = ExportOptions(),
    val photoToExport: Photo? = null
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()
    
    // Semaphore to limit concurrent image loading operations
    // Max 3 concurrent image decoding to prevent "Image decoding logging dropped" warnings
    private val imageLoadingSemaphore = Semaphore(3)
    
    // Track if photos have been loaded to prevent duplicate loading
    private var hasLoadedPhotos = false
    
    init {
        // Don't load photos automatically - wait for screen to be visible
        // This prevents 6.5s delay on app launch
    }
    
    /**
     * Called when gallery screen becomes visible
     * Always refreshes photos from MediaStore to ensure newly saved photos are visible
     */
    fun onScreenVisible() {
        if (!hasLoadedPhotos) {
            // First time: set up flow collection
            loadPhotos()
            hasLoadedPhotos = true
        }
        // Always refresh from MediaStore to pick up newly saved photos
        viewModelScope.launch {
            photoRepository.refreshPhotos()
        }
    }
    
    fun loadPhotos() {
        viewModelScope.launch {
            try {
                // Combine all flows for efficient updates
                combine(
                    photoRepository.getAllPhotos(),
                    photoRepository.getFavoritePhotos(),
                    photoRepository.getLoadingState(),
                    photoRepository.hasMorePages()
                ) { photos, favorites, isLoading, hasMorePages ->
                    _uiState.value.copy(
                        photos = photos,
                        favoritePhotos = favorites,
                        isLoading = isLoading,
                        hasMorePages = hasMorePages,
                        error = null
                    )
                }.catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Failed to load photos: ${e.message}"
                    )
                }.collect { newState ->
                    _uiState.value = newState
                }
                    
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load photos: ${e.message}"
                )
            }
        }
    }
    
    fun loadNextPage() {
        viewModelScope.launch {
            photoRepository.loadNextPage()
        }
    }
    
    fun toggleFavorite(photoId: String) {
        viewModelScope.launch {
            try {
                photoRepository.toggleFavorite(photoId)
                // Reload photos to reflect the change
                loadPhotos()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to toggle favorite: ${e.message}"
                )
            }
        }
    }
    
    fun deletePhoto(photoId: String) {
        viewModelScope.launch {
            try {
                val result = photoRepository.deletePhoto(photoId)
                if (result.isSuccess) {
                    // Remove from current state immediately for better UX
                    val currentPhotos = _uiState.value.photos.filter { it.id != photoId }
                    val currentFavorites = _uiState.value.favoritePhotos.filter { it.id != photoId }
                    
                    _uiState.value = _uiState.value.copy(
                        photos = currentPhotos,
                        favoritePhotos = currentFavorites,
                        selectedPhoto = if (_uiState.value.selectedPhoto?.id == photoId) null else _uiState.value.selectedPhoto
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Failed to delete photo"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to delete photo: ${e.message}"
                )
            }
        }
    }
    
    fun selectPhoto(photo: Photo) {
        _uiState.value = _uiState.value.copy(selectedPhoto = photo)
    }
    
    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedPhoto = null)
    }
    
    fun toggleShowFavoritesOnly() {
        _uiState.value = _uiState.value.copy(
            showFavoritesOnly = !_uiState.value.showFavoritesOnly
        )
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    fun refreshPhotos() {
        viewModelScope.launch {
            // Actually refresh from MediaStore, not just re-collect the flow
            photoRepository.refreshPhotos()
        }
    }
    
    fun setSortOption(sortOption: SortOption) {
        _uiState.value = _uiState.value.copy(sortOption = sortOption)
        applySorting()
    }
    
    private fun applySorting() {
        val sortedPhotos = when (_uiState.value.sortOption) {
            SortOption.DATE_TAKEN_DESC -> _uiState.value.photos.sortedByDescending { it.timestamp }
            SortOption.DATE_TAKEN_ASC -> _uiState.value.photos.sortedBy { it.timestamp }
            SortOption.DATE_MODIFIED_DESC -> _uiState.value.photos.sortedByDescending { it.timestamp }
            SortOption.DATE_MODIFIED_ASC -> _uiState.value.photos.sortedBy { it.timestamp }
            SortOption.FILE_SIZE_DESC -> _uiState.value.photos.sortedByDescending { it.size }
            SortOption.FILE_SIZE_ASC -> _uiState.value.photos.sortedBy { it.size }
            SortOption.NAME_ASC -> _uiState.value.photos.sortedBy { it.name }
            SortOption.NAME_DESC -> _uiState.value.photos.sortedByDescending { it.name }
            SortOption.FAVORITES_FIRST -> _uiState.value.photos.sortedByDescending { it.isFavorite }
        }
        
        _uiState.value = _uiState.value.copy(photos = sortedPhotos)
    }
    
    // Multi-selection methods
    fun toggleSelectionMode() {
        _uiState.value = _uiState.value.copy(
            selectionMode = !_uiState.value.selectionMode,
            selectedPhotos = if (!_uiState.value.selectionMode) emptySet() else _uiState.value.selectedPhotos
        )
    }
    
    fun togglePhotoSelection(photoId: String) {
        val currentSelection = _uiState.value.selectedPhotos
        val newSelection = if (photoId in currentSelection) {
            currentSelection - photoId
        } else {
            currentSelection + photoId
        }
        _uiState.value = _uiState.value.copy(selectedPhotos = newSelection)
    }
    
    fun selectAllPhotos() {
        val photosToShow = if (_uiState.value.showFavoritesOnly) {
            _uiState.value.favoritePhotos
        } else {
            _uiState.value.photos
        }
        _uiState.value = _uiState.value.copy(
            selectedPhotos = photosToShow.map { it.id }.toSet()
        )
    }
    
    fun clearSelectedPhotos() {
        _uiState.value = _uiState.value.copy(selectedPhotos = emptySet())
    }
    
    fun deleteSelectedPhotos() {
        viewModelScope.launch {
            val selectedIds = _uiState.value.selectedPhotos.toList()
            var successCount = 0
            var errorCount = 0
            
            selectedIds.forEach { photoId ->
                try {
                    val result = photoRepository.deletePhoto(photoId)
                    if (result.isSuccess) {
                        successCount++
                    } else {
                        errorCount++
                    }
                } catch (e: Exception) {
                    errorCount++
                }
            }
            
            // Update state
            val remainingPhotos = _uiState.value.photos.filter { it.id !in selectedIds }
            val remainingFavorites = _uiState.value.favoritePhotos.filter { it.id !in selectedIds }
            
            _uiState.value = _uiState.value.copy(
                photos = remainingPhotos,
                favoritePhotos = remainingFavorites,
                selectedPhotos = emptySet(),
                selectionMode = false,
                error = if (errorCount > 0) {
                    "Deleted $successCount photo(s). Failed to delete $errorCount photo(s)."
                } else {
                    null
                }
            )
        }
    }
    
    fun favoriteSelectedPhotos() {
        viewModelScope.launch {
            val selectedIds = _uiState.value.selectedPhotos.toList()
            var successCount = 0
            
            selectedIds.forEach { photoId ->
                try {
                    val photo = _uiState.value.photos.find { it.id == photoId }
                    if (photo != null && !photo.isFavorite) {
                        photoRepository.toggleFavorite(photoId)
                        successCount++
                    }
                } catch (e: Exception) {
                    // Continue with other photos
                }
            }
            
            // Reload photos to reflect changes
            loadPhotos()
            
            _uiState.value = _uiState.value.copy(
                selectedPhotos = emptySet(),
                selectionMode = false
            )
        }
    }
    
    fun unfavoriteSelectedPhotos() {
        viewModelScope.launch {
            val selectedIds = _uiState.value.selectedPhotos.toList()
            var successCount = 0
            
            selectedIds.forEach { photoId ->
                try {
                    val photo = _uiState.value.photos.find { it.id == photoId }
                    if (photo != null && photo.isFavorite) {
                        photoRepository.toggleFavorite(photoId)
                        successCount++
                    }
                } catch (e: Exception) {
                    // Continue with other photos
                }
            }
            
            // Reload photos to reflect changes
            loadPhotos()
            
            _uiState.value = _uiState.value.copy(
                selectedPhotos = emptySet(),
                selectionMode = false
            )
        }
    }
    
    fun shareSelectedPhotos(onShare: (List<String>) -> Unit) {
        val selectedIds = _uiState.value.selectedPhotos.toList()
        val selectedUris = _uiState.value.photos
            .filter { it.id in selectedIds }
            .map { it.uri.toString() }
        
        if (selectedUris.isNotEmpty()) {
            onShare(selectedUris)
        }
    }
    
    // Export functionality
    fun showExportDialog(photo: Photo) {
        _uiState.value = _uiState.value.copy(
            showExportDialog = true,
            photoToExport = photo
        )
    }
    
    fun hideExportDialog() {
        _uiState.value = _uiState.value.copy(
            showExportDialog = false,
            photoToExport = null
        )
    }
    
    fun updateExportOptions(options: ExportOptions) {
        _uiState.value = _uiState.value.copy(exportOptions = options)
    }
    
    fun exportPhoto(destinationUri: Uri, onComplete: (Result<Uri>) -> Unit) {
        val photo = _uiState.value.photoToExport ?: return
        val exportOptions = _uiState.value.exportOptions
        
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                // Load the bitmap with throttling to prevent overload
                val bitmap = withContext(Dispatchers.IO) {
                    imageLoadingSemaphore.withPermit {
                        ImageUtils.loadBitmap(context, photo.uri, maxWidth = 4096, maxHeight = 4096)
                    }
                } ?: run {
                    onComplete(Result.failure(Exception("Failed to load image")))
                    _uiState.value = _uiState.value.copy(isLoading = false, showExportDialog = false)
                    return@launch
                }
                
                // Resize if needed
                val resizedBitmap = if (exportOptions.resizePercentage < 100) {
                    val newWidth = (bitmap.width * exportOptions.resizePercentage / 100)
                    val newHeight = (bitmap.height * exportOptions.resizePercentage / 100)
                    Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                } else bitmap
                
                // Determine format and quality
                val compressFormat = when (exportOptions.format) {
                    ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    ExportFormat.PNG -> Bitmap.CompressFormat.PNG
                    ExportFormat.WEBP -> Bitmap.CompressFormat.WEBP
                }
                val quality = when (exportOptions.quality) {
                    ExportQuality.LOW -> 60
                    ExportQuality.MEDIUM -> 80
                    ExportQuality.HIGH -> 95
                    ExportQuality.MAXIMUM -> 100
                }
                
                // Write to destination URI
                val result = withContext(Dispatchers.IO) {
                    ImageUtils.writeBitmapToUriWithExif(
                        context = context,
                        bitmap = resizedBitmap,
                        destinationUri = destinationUri,
                        sourceUri = photo.uri,
                        format = compressFormat,
                        quality = quality
                    )
                }
                
                _uiState.value = _uiState.value.copy(isLoading = false, showExportDialog = false)
                onComplete(result)
                
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Export failed: ${e.message}"
                )
                onComplete(Result.failure(e))
            }
        }
    }
}
