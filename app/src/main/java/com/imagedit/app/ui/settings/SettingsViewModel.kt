package com.imagedit.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedit.app.data.repository.SettingsRepository
import com.imagedit.app.domain.model.ProcessingMode
import com.imagedit.app.domain.model.SmartEnhancementSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing settings screen state and user interactions.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    val settings: StateFlow<SmartEnhancementSettings> = settingsRepository.settings
    
    /**
     * Updates the processing mode setting.
     */
    fun updateProcessingMode(mode: ProcessingMode) {
        viewModelScope.launch {
            settingsRepository.updateProcessingMode(mode)
        }
    }
    
    /**
     * Updates the auto scene detection setting.
     */
    fun updateAutoSceneDetection(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoSceneDetection(enabled)
        }
    }
    
    /**
     * Updates the portrait enhancement intensity.
     */
    fun updatePortraitIntensity(intensity: Float) {
        viewModelScope.launch {
            settingsRepository.updatePortraitIntensity(intensity)
        }
    }
    
    /**
     * Updates the landscape enhancement enabled setting.
     */
    fun updateLandscapeEnhancement(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateLandscapeEnhancement(enabled)
        }
    }
    
    /**
     * Updates whether smart enhance preserves manual adjustments.
     */
    fun updatePreserveManualAdjustments(preserve: Boolean) {
        viewModelScope.launch {
            settingsRepository.updatePreserveManualAdjustments(preserve)
        }
    }
}