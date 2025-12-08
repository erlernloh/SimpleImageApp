package com.imagedit.app.ui.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imagedit.app.domain.model.HealingBrush
import com.imagedit.app.ui.accessibility.AccessibilityUtils
import com.imagedit.app.ui.accessibility.enhancedAccessibility
import com.imagedit.app.ui.accessibility.accessibleSlider

/**
 * Panel containing healing tool controls including brush settings and actions
 */
@Composable
fun HealingToolPanel(
    brushSettings: HealingBrush,
    onBrushSettingsChange: (HealingBrush) -> Unit,
    onClearStrokes: () -> Unit,
    onUndoStroke: () -> Unit,
    onApplyHealing: () -> Unit,
    onCancel: () -> Unit,
    hasStrokes: Boolean,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Healing Tool",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Undo button
                    IconButton(
                        onClick = onUndoStroke,
                        enabled = hasStrokes && !isProcessing,
                        modifier = Modifier.enhancedAccessibility(
                            contentDescription = "Undo last healing stroke",
                            role = Role.Button,
                            onClick = onUndoStroke,
                            stateDescription = if (hasStrokes) "Available" else "No strokes to undo",
                            disabled = !hasStrokes || isProcessing
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Undo,
                            contentDescription = null
                        )
                    }
                    
                    // Clear button
                    IconButton(
                        onClick = onClearStrokes,
                        enabled = hasStrokes && !isProcessing,
                        modifier = Modifier.enhancedAccessibility(
                            contentDescription = "Clear all healing strokes",
                            role = Role.Button,
                            onClick = onClearStrokes,
                            stateDescription = if (hasStrokes) "Available" else "No strokes to clear",
                            disabled = !hasStrokes || isProcessing
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = null
                        )
                    }
                }
            }
            
            // Brush size control
            BrushSizeControl(
                size = brushSettings.size,
                onSizeChange = { newSize ->
                    onBrushSettingsChange(brushSettings.copy(size = newSize))
                },
                enabled = !isProcessing
            )
            
            // Brush hardness control
            BrushHardnessControl(
                hardness = brushSettings.hardness,
                onHardnessChange = { newHardness ->
                    onBrushSettingsChange(brushSettings.copy(hardness = newHardness))
                },
                enabled = !isProcessing
            )
            
            // Brush opacity control
            BrushOpacityControl(
                opacity = brushSettings.opacity,
                onOpacityChange = { newOpacity ->
                    onBrushSettingsChange(brushSettings.copy(opacity = newOpacity))
                },
                enabled = !isProcessing
            )
            
            // Pressure sensitivity toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pressure Sensitive",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Switch(
                    checked = brushSettings.pressureSensitive,
                    onCheckedChange = { enabled ->
                        onBrushSettingsChange(brushSettings.copy(pressureSensitive = enabled))
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.enhancedAccessibility(
                        contentDescription = "Pressure sensitivity for healing brush",
                        role = Role.Switch,
                        stateDescription = if (brushSettings.pressureSensitive) "Enabled" else "Disabled",
                        disabled = isProcessing
                    )
                )
            }
            
            Divider()
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Cancel button
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing
                ) {
                    Text("Cancel")
                }
                
                // Apply button
                Button(
                    onClick = onApplyHealing,
                    modifier = Modifier.weight(1f),
                    enabled = hasStrokes && !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Apply Healing")
                    }
                }
            }
        }
    }
}

@Composable
private fun BrushSizeControl(
    size: Float,
    onSizeChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Brush Size",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${size.toInt()}px",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Slider(
            value = size,
            onValueChange = onSizeChange,
            valueRange = HealingBrush.MIN_SIZE..HealingBrush.MAX_SIZE,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .accessibleSlider(
                    value = size,
                    valueRange = HealingBrush.MIN_SIZE..HealingBrush.MAX_SIZE,
                    parameterName = "Brush size",
                    onValueChange = onSizeChange,
                    unit = "pixels"
                )
        )
    }
}

@Composable
private fun BrushHardnessControl(
    hardness: Float,
    onHardnessChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Hardness",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${(hardness * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Slider(
            value = hardness,
            onValueChange = onHardnessChange,
            valueRange = HealingBrush.MIN_HARDNESS..HealingBrush.MAX_HARDNESS,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .accessibleSlider(
                    value = hardness,
                    valueRange = HealingBrush.MIN_HARDNESS..HealingBrush.MAX_HARDNESS,
                    parameterName = "Brush hardness",
                    onValueChange = onHardnessChange,
                    unit = "percent"
                )
        )
    }
}

@Composable
private fun BrushOpacityControl(
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Opacity",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${(opacity * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Slider(
            value = opacity,
            onValueChange = onOpacityChange,
            valueRange = HealingBrush.MIN_OPACITY..HealingBrush.MAX_OPACITY,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .accessibleSlider(
                    value = opacity,
                    valueRange = HealingBrush.MIN_OPACITY..HealingBrush.MAX_OPACITY,
                    parameterName = "Brush opacity",
                    onValueChange = onOpacityChange,
                    unit = "percent"
                )
        )
    }
}