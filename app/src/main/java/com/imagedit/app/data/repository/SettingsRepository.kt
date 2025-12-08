package com.imagedit.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.imagedit.app.domain.model.ProcessingMode
import com.imagedit.app.domain.model.SmartEnhancementSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing app settings persistence and state.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("smart_enhancement_settings", Context.MODE_PRIVATE)
    
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<SmartEnhancementSettings> = _settings.asStateFlow()
    
    /**
     * Updates the processing mode setting.
     */
    fun updateProcessingMode(mode: ProcessingMode) {
        val currentSettings = _settings.value
        val newSettings = currentSettings.copy(processingMode = mode)
        saveSettings(newSettings)
        _settings.value = newSettings
    }
    
    /**
     * Updates the auto scene detection setting.
     */
    fun updateAutoSceneDetection(enabled: Boolean) {
        val currentSettings = _settings.value
        val newSettings = currentSettings.copy(autoSceneDetection = enabled)
        saveSettings(newSettings)
        _settings.value = newSettings
    }
    
    /**
     * Updates the portrait enhancement intensity.
     */
    fun updatePortraitIntensity(intensity: Float) {
        val currentSettings = _settings.value
        val newSettings = currentSettings.copy(portraitEnhancementIntensity = intensity)
        saveSettings(newSettings)
        _settings.value = newSettings
    }
    
    /**
     * Updates the landscape enhancement enabled setting.
     */
    fun updateLandscapeEnhancement(enabled: Boolean) {
        val currentSettings = _settings.value
        val newSettings = currentSettings.copy(landscapeEnhancementEnabled = enabled)
        saveSettings(newSettings)
        _settings.value = newSettings
    }
    
    /**
     * Updates whether smart enhance preserves manual adjustments.
     */
    fun updatePreserveManualAdjustments(preserve: Boolean) {
        val currentSettings = _settings.value
        val newSettings = currentSettings.copy(smartEnhancePreservesManualAdjustments = preserve)
        saveSettings(newSettings)
        _settings.value = newSettings
    }
    
    /**
     * Gets the current settings synchronously.
     */
    fun getCurrentSettings(): SmartEnhancementSettings {
        return _settings.value
    }
    
    private fun loadSettings(): SmartEnhancementSettings {
        return SmartEnhancementSettings(
            processingMode = ProcessingMode.valueOf(
                sharedPreferences.getString("processing_mode", ProcessingMode.MEDIUM.name) 
                    ?: ProcessingMode.MEDIUM.name
            ),
            autoSceneDetection = sharedPreferences.getBoolean("auto_scene_detection", true),
            portraitEnhancementIntensity = sharedPreferences.getFloat("portrait_intensity", 0.5f),
            landscapeEnhancementEnabled = sharedPreferences.getBoolean("landscape_enabled", true),
            smartEnhancePreservesManualAdjustments = sharedPreferences.getBoolean("preserve_manual", true)
        )
    }
    
    private fun saveSettings(settings: SmartEnhancementSettings) {
        sharedPreferences.edit().apply {
            putString("processing_mode", settings.processingMode.name)
            putBoolean("auto_scene_detection", settings.autoSceneDetection)
            putFloat("portrait_intensity", settings.portraitEnhancementIntensity)
            putBoolean("landscape_enabled", settings.landscapeEnhancementEnabled)
            putBoolean("preserve_manual", settings.smartEnhancePreservesManualAdjustments)
            apply()
        }
    }
}