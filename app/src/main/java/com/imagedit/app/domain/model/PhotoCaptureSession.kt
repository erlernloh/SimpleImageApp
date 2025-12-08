package com.imagedit.app.domain.model

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a photo capture session where multiple photos can be captured
 * before selecting one to edit.
 */
@Serializable
data class PhotoCaptureSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val capturedPhotos: List<CapturedPhotoItem> = emptyList(),
    val selectedPhotoUri: String? = null,
    val selectedPhotoUris: Set<String> = emptySet(), // For multi-selection
    val isMultiSelectMode: Boolean = false,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(sessionId)
        dest.writeTypedList(capturedPhotos)
        dest.writeString(selectedPhotoUri)
        dest.writeStringList(selectedPhotoUris.toList())
        dest.writeByte(if (isMultiSelectMode) 1 else 0)
        dest.writeByte(if (isActive) 1 else 0)
        dest.writeLong(createdAt)
    }

    companion object CREATOR : Parcelable.Creator<PhotoCaptureSession> {
        override fun createFromParcel(parcel: Parcel): PhotoCaptureSession {
            val sessionId = parcel.readString() ?: ""
            val captured = mutableListOf<CapturedPhotoItem>()
            parcel.readTypedList(captured, CapturedPhotoItem.CREATOR)
            val selected = parcel.readString()
            val selectedUris = mutableListOf<String>()
            parcel.readStringList(selectedUris)
            val multiSelectMode = parcel.readByte().toInt() != 0
            val active = parcel.readByte().toInt() != 0
            val createdAt = parcel.readLong()
            return PhotoCaptureSession(
                sessionId = sessionId,
                capturedPhotos = captured,
                selectedPhotoUri = selected,
                selectedPhotoUris = selectedUris.toSet(),
                isMultiSelectMode = multiSelectMode,
                isActive = active,
                createdAt = createdAt
            )
        }

        override fun newArray(size: Int): Array<PhotoCaptureSession?> = arrayOfNulls(size)
    }
    
    /**
     * Check if session has any captured photos
     */
    fun hasPhotos(): Boolean = capturedPhotos.isNotEmpty()
    
    /**
     * Get the number of photos in this session
     */
    fun photoCount(): Int = capturedPhotos.size
    
    /**
     * Check if a specific photo is selected (single selection mode)
     */
    fun isPhotoSelected(uri: Uri): Boolean = selectedPhotoUri == uri.toString()
    
    /**
     * Check if a specific photo is selected in multi-select mode
     */
    fun isPhotoMultiSelected(uri: Uri): Boolean = uri.toString() in selectedPhotoUris
    
    /**
     * Get count of selected photos in multi-select mode
     */
    fun multiSelectCount(): Int = selectedPhotoUris.size
}

/**
 * Represents a single captured photo item in the session
 */
@Serializable
data class CapturedPhotoItem(
    val uri: String,
    val thumbnailUri: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isSelected: Boolean = false
) : Parcelable {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(uri)
        dest.writeString(thumbnailUri)
        dest.writeLong(timestamp)
        dest.writeByte(if (isSelected) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<CapturedPhotoItem> {
        override fun createFromParcel(parcel: Parcel): CapturedPhotoItem {
            val uri = parcel.readString() ?: ""
            val thumb = parcel.readString()
            val ts = parcel.readLong()
            val selected = parcel.readByte().toInt() != 0
            return CapturedPhotoItem(
                uri = uri,
                thumbnailUri = thumb,
                timestamp = ts,
                isSelected = selected
            )
        }

        override fun newArray(size: Int): Array<CapturedPhotoItem?> = arrayOfNulls(size)
    }
    
    /**
     * Get Uri object from string
     */
    fun getUri(): Uri = Uri.parse(uri)
    
    /**
     * Get thumbnail Uri object from string
     */
    fun getThumbnailUri(): Uri? = thumbnailUri?.let { Uri.parse(it) }
}
