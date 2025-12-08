package com.imagedit.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.imagedit.app.domain.model.AdjustmentParameters
import com.imagedit.app.domain.model.FilmGrain
import com.imagedit.app.domain.model.LensEffects
import com.imagedit.app.domain.repository.FilterPreset
import com.imagedit.app.domain.repository.PresetRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "presets")

@Singleton
class PresetRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PresetRepository {
    
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _presets = MutableStateFlow<List<FilterPreset>>(emptyList())
    private val presets = _presets.asStateFlow()
    
    companion object {
        private val USER_PRESETS_KEY = stringPreferencesKey("user_presets")
    }
    
    override fun getAllPresets(): Flow<List<FilterPreset>> = presets
    
    override fun getBuiltInPresets(): Flow<List<FilterPreset>> = 
        presets.map { list -> list.filter { it.isBuiltIn } }
    
    override fun getUserPresets(): Flow<List<FilterPreset>> = 
        presets.map { list -> list.filter { !it.isBuiltIn } }
    
    override suspend fun getPresetById(id: String): FilterPreset? {
        return _presets.value.find { it.id == id }
    }
    
    override suspend fun savePreset(preset: FilterPreset): Result<Unit> {
        return try {
            val currentPresets = _presets.value.toMutableList()
            val existingIndex = currentPresets.indexOfFirst { it.id == preset.id }
            
            if (existingIndex >= 0) {
                currentPresets[existingIndex] = preset
            } else {
                currentPresets.add(preset)
            }
            
            _presets.value = currentPresets
            
            // Persist user presets to DataStore
            saveUserPresetsToStorage()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun deletePreset(id: String): Result<Unit> {
        return try {
            val currentPresets = _presets.value.toMutableList()
            currentPresets.removeAll { it.id == id && !it.isBuiltIn }
            _presets.value = currentPresets
            
            // Persist changes to DataStore
            saveUserPresetsToStorage()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun initializeBuiltInPresets() {
        // First, load user presets from storage
        val userPresets = loadUserPresetsFromStorageSync()
        
        // Then create built-in presets
        val builtInPresets = listOf(
            FilterPreset(
                id = "vintage",
                name = "Vintage",
                adjustments = AdjustmentParameters(
                    brightness = 0.1f,
                    contrast = 0.2f,
                    saturation = -0.3f,
                    warmth = 0.4f,
                    tint = 0.1f
                ),
                filmGrain = FilmGrain(amount = 0.3f, size = 1.2f, roughness = 0.8f),
                lensEffects = LensEffects(vignetteAmount = 0.4f),
                isBuiltIn = true
            ),
            FilterPreset(
                id = "dramatic",
                name = "Dramatic",
                adjustments = AdjustmentParameters(
                    brightness = -0.1f,
                    contrast = 0.5f,
                    saturation = 0.2f,
                    highlights = -0.3f,
                    shadows = 0.2f
                ),
                lensEffects = LensEffects(vignetteAmount = 0.6f),
                isBuiltIn = true
            ),
            FilterPreset(
                id = "bright",
                name = "Bright & Airy",
                adjustments = AdjustmentParameters(
                    brightness = 0.3f,
                    contrast = -0.1f,
                    highlights = -0.2f,
                    shadows = 0.3f,
                    whites = 0.2f
                ),
                isBuiltIn = true
            ),
            FilterPreset(
                id = "bw_classic",
                name = "Classic B&W",
                adjustments = AdjustmentParameters(
                    saturation = -1.0f,
                    contrast = 0.3f,
                    brightness = 0.1f
                ),
                isBuiltIn = true
            ),
            FilterPreset(
                id = "warm_sunset",
                name = "Warm Sunset",
                adjustments = AdjustmentParameters(
                    warmth = 0.6f,
                    tint = 0.2f,
                    saturation = 0.2f,
                    highlights = -0.2f
                ),
                lensEffects = LensEffects(vignetteAmount = 0.3f),
                isBuiltIn = true
            ),
            FilterPreset(
                id = "cool_blue",
                name = "Cool Blue",
                adjustments = AdjustmentParameters(
                    warmth = -0.4f,
                    tint = -0.2f,
                    saturation = 0.1f,
                    shadows = 0.1f
                ),
                isBuiltIn = true
            )
        )
        
        // Combine built-in and user presets (built-in first, then user)
        _presets.value = builtInPresets + userPresets
    }
    
    private suspend fun loadUserPresetsFromStorageSync(): List<FilterPreset> {
        return try {
            val preferences = context.dataStore.data.first()
            val json = preferences[USER_PRESETS_KEY] ?: return emptyList()
            val type = object : TypeToken<List<FilterPreset>>() {}.type
            gson.fromJson<List<FilterPreset>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private suspend fun saveUserPresetsToStorage() {
        try {
            val userPresets = _presets.value.filter { !it.isBuiltIn }
            val json = gson.toJson(userPresets)
            context.dataStore.edit { preferences ->
                preferences[USER_PRESETS_KEY] = json
            }
        } catch (e: Exception) {
            // Silently fail - presets will remain in memory for current session
        }
    }
}
