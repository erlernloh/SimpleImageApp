package com.imagedit.app.domain.repository

import android.net.Uri
import com.imagedit.app.domain.model.Photo
import kotlinx.coroutines.flow.Flow

interface PhotoRepository {
    fun getAllPhotos(): Flow<List<Photo>>
    fun getFavoritePhotos(): Flow<List<Photo>>
    suspend fun getPhotoById(id: String): Photo?
    suspend fun savePhoto(photo: Photo): Result<Uri>
    suspend fun deletePhoto(id: String): Result<Unit>
    suspend fun toggleFavorite(id: String): Result<Unit>
    suspend fun updatePhoto(photo: Photo): Result<Unit>
}
