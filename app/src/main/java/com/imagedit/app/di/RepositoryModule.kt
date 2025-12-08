package com.imagedit.app.di

import com.imagedit.app.data.repository.EnhancedImageProcessor
import com.imagedit.app.data.repository.PhotoRepositoryImpl
import com.imagedit.app.data.repository.PresetRepositoryImpl
import com.imagedit.app.domain.repository.ImageProcessor
import com.imagedit.app.domain.repository.PhotoRepository
import com.imagedit.app.domain.repository.PresetRepository
import com.imagedit.app.domain.repository.SmartProcessor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindPhotoRepository(
        photoRepositoryImpl: PhotoRepositoryImpl
    ): PhotoRepository
    
    @Binds
    @Singleton
    abstract fun bindImageProcessor(
        imageProcessorImpl: EnhancedImageProcessor
    ): ImageProcessor
    
    @Binds
    @Singleton
    abstract fun bindSmartProcessor(
        enhancedImageProcessor: EnhancedImageProcessor
    ): SmartProcessor
    
    @Binds
    @Singleton
    abstract fun bindPresetRepository(
        presetRepositoryImpl: PresetRepositoryImpl
    ): PresetRepository
}
