package com.imagedit.app.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.imagedit.app.domain.model.Photo
import com.imagedit.app.domain.repository.PhotoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PhotoRepository {
    
    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    private val _favoritePhotos = MutableStateFlow<List<Photo>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _hasMorePages = MutableStateFlow(true)
    
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentOffset = 0
    private val pageSize = 15 // Reduced from 30 for faster initial load
    
    // DataStore for favorites persistence
    private val Context.favoritesDataStore by preferencesDataStore(name = "favorites_prefs")
    private val FAVORITES_KEY = stringSetPreferencesKey("favorite_ids")
    private val favoritesFlow: Flow<Set<String>> = context.favoritesDataStore.data.map { prefs ->
        prefs[FAVORITES_KEY] ?: emptySet()
    }
    
    companion object {
        private const val TAG = "PhotoRepositoryImpl"
        
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE
        )
    }
    
    init {
        loadInitialPhotos()
        // Keep in-memory lists synced with persisted favorites
        repositoryScope.launch {
            favoritesFlow.collect { favs ->
                // Update favorite flags on current photos
                val updated = _photos.value.map { it.copy(isFavorite = it.id in favs) }
                _photos.value = updated
                _favoritePhotos.value = updated.filter { it.isFavorite }
            }
        }
    }
    
    override fun getAllPhotos(): Flow<List<Photo>> = _photos.asStateFlow()
    
    override fun getFavoritePhotos(): Flow<List<Photo>> = _favoritePhotos.asStateFlow()
    
    override fun getLoadingState(): Flow<Boolean> = _isLoading.asStateFlow()
    
    override fun hasMorePages(): Flow<Boolean> = _hasMorePages.asStateFlow()
    
    override suspend fun getPhotoById(id: String): Photo? {
        return _photos.value.find { it.id == id }
    }
    
    override suspend fun savePhoto(photo: Photo): Result<Uri> {
        return withContext(Dispatchers.IO) {
            try {
                // Add to internal list
                val currentPhotos = _photos.value.toMutableList()
                val existingIndex = currentPhotos.indexOfFirst { it.id == photo.id }
                
                if (existingIndex >= 0) {
                    currentPhotos[existingIndex] = photo
                } else {
                    currentPhotos.add(0, photo) // Add to beginning for recent photos
                }
                
                _photos.value = currentPhotos
                
                // Trigger MediaStore scan for new files
                context.sendBroadcast(
                    android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                        data = photo.uri
                    }
                )
                
                Result.success(photo.uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save photo", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun deletePhoto(id: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val photo = getPhotoById(id)
                if (photo != null) {
                    // Delete from MediaStore
                    val deletedRows = context.contentResolver.delete(
                        photo.uri,
                        null,
                        null
                    )
                    
                    if (deletedRows > 0) {
                        // Remove from internal lists
                        _photos.value = _photos.value.filter { it.id != id }
                        _favoritePhotos.value = _favoritePhotos.value.filter { it.id != id }
                        
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("Failed to delete photo from MediaStore"))
                    }
                } else {
                    Result.failure(Exception("Photo not found"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete photo", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun toggleFavorite(id: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Persist toggle in DataStore
                context.favoritesDataStore.edit { prefs ->
                    val set = prefs[FAVORITES_KEY]?.toMutableSet() ?: mutableSetOf()
                    if (id in set) set.remove(id) else set.add(id)
                    prefs[FAVORITES_KEY] = set
                }
                // In-memory lists will be updated by favoritesFlow collector
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle favorite", e)
                Result.failure(e)
            }
        }
    }
    
    override suspend fun updatePhoto(photo: Photo): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val currentPhotos = _photos.value.toMutableList()
                val photoIndex = currentPhotos.indexOfFirst { it.id == photo.id }
                
                if (photoIndex >= 0) {
                    currentPhotos[photoIndex] = photo
                    _photos.value = currentPhotos
                    
                    // Update favorites if needed
                    if (photo.isFavorite) {
                        val currentFavorites = _favoritePhotos.value.toMutableList()
                        val favoriteIndex = currentFavorites.indexOfFirst { it.id == photo.id }
                        if (favoriteIndex >= 0) {
                            currentFavorites[favoriteIndex] = photo
                        } else {
                            currentFavorites.add(photo)
                        }
                        _favoritePhotos.value = currentFavorites
                    } else {
                        _favoritePhotos.value = _favoritePhotos.value.filter { it.id != photo.id }
                    }
                    
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Photo not found"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update photo", e)
                Result.failure(e)
            }
        }
    }
    
    private fun loadInitialPhotos() {
        repositoryScope.launch {
            // PERFORMANCE: Increased delay to let app UI fully initialize first
            // This helps prevent "Skipped frames" on startup by deferring MediaStore queries
            kotlinx.coroutines.delay(400)
            loadPhotosPage()
        }
    }
    
    override suspend fun loadNextPage() {
        if (_isLoading.value || !_hasMorePages.value) return
        
        repositoryScope.launch {
            loadPhotosPage()
        }
    }
    
    private suspend fun loadPhotosPage() {
        if (_isLoading.value) return
        
        _isLoading.value = true
        
        try {
            val newPhotos = mutableListOf<Photo>()
            val contentResolver: ContentResolver = context.contentResolver
            
            val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use Bundle-based query for proper pagination (Android 8.0+)
                val queryArgs = Bundle().apply {
                    putStringArray(
                        android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(MediaStore.Images.Media.DATE_ADDED)
                    )
                    putInt(
                        android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                    )
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, pageSize)
                    putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, currentOffset)
                }
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PROJECTION,
                    queryArgs,
                    null
                )
            } else {
                // Fallback for older Android versions - load all and paginate in memory
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PROJECTION,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )
            }
            
            cursor?.use { c ->
                val idColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateAddedColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val dateTakenColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val widthColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val sizeColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                
                // For older Android versions, skip to the current offset manually
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && currentOffset > 0) {
                    var skipped = 0
                    while (skipped < currentOffset && c.moveToNext()) {
                        skipped++
                    }
                }
                
                var count = 0
                // Snapshot favorites to apply to loaded page
                val currentFavs = try { favoritesFlow.first() } catch (e: Exception) { emptySet() }
                while (c.moveToNext() && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || count < pageSize)) {
                    count++
                    val id = c.getLong(idColumn)
                    val name = c.getString(nameColumn) ?: "Unknown"
                    val dateAdded = c.getLong(dateAddedColumn) * 1000 // Convert to milliseconds
                    val dateTaken = c.getLong(dateTakenColumn) // Already in milliseconds
                    val width = c.getInt(widthColumn)
                    val height = c.getInt(heightColumn)
                    val size = c.getLong(sizeColumn)
                    
                    val contentUri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    
                    // Use DATE_TAKEN if available, otherwise fall back to DATE_ADDED
                    val timestamp = if (dateTaken > 0) dateTaken else dateAdded
                    
                    val photo = Photo(
                        id = id.toString(),
                        uri = contentUri,
                        name = name,
                        timestamp = timestamp,
                        width = width,
                        height = height,
                        size = size,
                        isFavorite = id.toString() in currentFavs,
                        hasEdits = false,
                        tags = emptyList(),
                        metadata = emptyMap()
                    )
                    
                    newPhotos.add(photo)
                }
            }
            
            // Update photos list
            val currentPhotos = _photos.value.toMutableList()
            currentPhotos.addAll(newPhotos)
            _photos.value = currentPhotos
            
            // Update pagination state
            currentOffset += pageSize
            _hasMorePages.value = newPhotos.size == pageSize
            
            Log.d(TAG, "Loaded ${newPhotos.size} photos (page), total: ${currentPhotos.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load photos page from MediaStore", e)
        } finally {
            _isLoading.value = false
        }
    }
    
    override fun refreshPhotos() {
        repositoryScope.launch {
            currentOffset = 0
            _hasMorePages.value = true
            _photos.value = emptyList()
            loadPhotosPage()
        }
    }
}
