package com.imagedit.app.domain.repository

import com.imagedit.app.domain.model.AdjustmentParameters
import com.imagedit.app.domain.model.FilmGrain
import com.imagedit.app.domain.model.LensEffects
import kotlinx.coroutines.flow.Flow

data class FilterPreset(
    val id: String,
    val name: String,
    val adjustments: AdjustmentParameters,
    val filmGrain: FilmGrain = FilmGrain.default(),
    val lensEffects: LensEffects = LensEffects.default(),
    val isBuiltIn: Boolean = false
)

interface PresetRepository {
    fun getAllPresets(): Flow<List<FilterPreset>>
    fun getBuiltInPresets(): Flow<List<FilterPreset>>
    fun getUserPresets(): Flow<List<FilterPreset>>
    suspend fun getPresetById(id: String): FilterPreset?
    suspend fun savePreset(preset: FilterPreset): Result<Unit>
    suspend fun deletePreset(id: String): Result<Unit>
    suspend fun initializeBuiltInPresets()
}
