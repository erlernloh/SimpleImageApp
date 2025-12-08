package com.imagedit.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.imagedit.app.domain.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val scrollState = rememberScrollState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Performance Mode Section
            PerformanceModeSection(
                currentMode = settings.processingMode,
                onModeSelected = viewModel::updateProcessingMode
            )
            
            Divider()
            
            // Smart Enhancement Settings
            SmartEnhancementSection(
                settings = settings,
                onAutoSceneDetectionChanged = viewModel::updateAutoSceneDetection,
                onPortraitIntensityChanged = viewModel::updatePortraitIntensity,
                onLandscapeEnhancementChanged = viewModel::updateLandscapeEnhancement,
                onPreserveManualChanged = viewModel::updatePreserveManualAdjustments
            )
        }
    }
}

@Composable
private fun PerformanceModeSection(
    currentMode: ProcessingMode,
    onModeSelected: (ProcessingMode) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Performance Mode",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Choose how to balance processing speed, battery usage, and image quality.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // Performance mode cards
        ProcessingMode.values().forEach { mode ->
            val modeInfo = getProcessingModeInfo(mode)
            PerformanceModeCard(
                modeInfo = modeInfo,
                isSelected = currentMode == mode,
                onSelected = { onModeSelected(mode) }
            )
        }
    }
}

@Composable
private fun PerformanceModeCard(
    modeInfo: ProcessingModeInfo,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        onClick = onSelected
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = modeInfo.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                RadioButton(
                    selected = isSelected,
                    onClick = onSelected
                )
            }
            
            Text(
                text = modeInfo.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Battery impact indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = getBatteryIcon(modeInfo.batteryImpact),
                        contentDescription = null,
                        tint = getBatteryColor(modeInfo.batteryImpact),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Battery: ${modeInfo.batteryImpact.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Processing speed indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Speed: ${modeInfo.processingSpeed.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Text(
                text = "Max resolution: ${modeInfo.maxResolution}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SmartEnhancementSection(
    settings: SmartEnhancementSettings,
    onAutoSceneDetectionChanged: (Boolean) -> Unit,
    onPortraitIntensityChanged: (Float) -> Unit,
    onLandscapeEnhancementChanged: (Boolean) -> Unit,
    onPreserveManualChanged: (Boolean) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Smart Enhancement Options",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        // Auto Scene Detection
        SettingItem(
            title = "Auto Scene Detection",
            description = "Automatically detect photo type and suggest enhancements",
            checked = settings.autoSceneDetection,
            onCheckedChange = onAutoSceneDetectionChanged
        )
        
        // Portrait Enhancement Intensity
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Default Portrait Enhancement Intensity",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Set the default intensity for portrait enhancements (${(settings.portraitEnhancementIntensity * 100).toInt()}%)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = settings.portraitEnhancementIntensity,
                onValueChange = onPortraitIntensityChanged,
                valueRange = 0f..1f,
                steps = 9 // 10% increments
            )
        }
        
        // Landscape Enhancement
        SettingItem(
            title = "Landscape Enhancement",
            description = "Enable enhanced processing for outdoor and landscape photos",
            checked = settings.landscapeEnhancementEnabled,
            onCheckedChange = onLandscapeEnhancementChanged
        )
        
        // Preserve Manual Adjustments
        SettingItem(
            title = "Preserve Manual Adjustments",
            description = "Keep existing manual adjustments when applying smart enhancements",
            checked = settings.smartEnhancePreservesManualAdjustments,
            onCheckedChange = onPreserveManualChanged
        )
    }
}

@Composable
private fun SettingItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun getBatteryIcon(impact: BatteryImpact): ImageVector {
    return when (impact) {
        BatteryImpact.LOW -> Icons.Default.BatteryFull
        BatteryImpact.MEDIUM -> Icons.Default.Battery3Bar
        BatteryImpact.HIGH -> Icons.Default.Battery1Bar
    }
}

@Composable
private fun getBatteryColor(impact: BatteryImpact): androidx.compose.ui.graphics.Color {
    return when (impact) {
        BatteryImpact.LOW -> MaterialTheme.colorScheme.primary
        BatteryImpact.MEDIUM -> MaterialTheme.colorScheme.tertiary
        BatteryImpact.HIGH -> MaterialTheme.colorScheme.error
    }
}

private fun getProcessingModeInfo(mode: ProcessingMode): ProcessingModeInfo {
    return when (mode) {
        ProcessingMode.LITE -> ProcessingModeInfo(
            mode = mode,
            title = "Lite Mode",
            description = "Fastest processing with lowest battery usage. Perfect for quick edits and older devices.",
            batteryImpact = BatteryImpact.LOW,
            processingSpeed = ProcessingSpeed.FAST,
            maxResolution = "1080p",
            features = listOf("Simplified algorithms", "Reduced memory usage", "Quick processing")
        )
        ProcessingMode.MEDIUM -> ProcessingModeInfo(
            mode = mode,
            title = "Medium Mode",
            description = "Balanced performance and quality. Good compromise between speed and results.",
            batteryImpact = BatteryImpact.MEDIUM,
            processingSpeed = ProcessingSpeed.BALANCED,
            maxResolution = "1440p",
            features = listOf("Standard algorithms", "Moderate memory usage", "Good quality")
        )
        ProcessingMode.ADVANCED -> ProcessingModeInfo(
            mode = mode,
            title = "Advanced Mode",
            description = "Highest quality processing with full resolution. Best results for professional editing.",
            batteryImpact = BatteryImpact.HIGH,
            processingSpeed = ProcessingSpeed.SLOW,
            maxResolution = "Full resolution",
            features = listOf("Complex algorithms", "Full quality processing", "Professional results")
        )
    }
}