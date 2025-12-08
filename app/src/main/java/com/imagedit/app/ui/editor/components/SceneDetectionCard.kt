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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.imagedit.app.domain.model.SceneAnalysis
import com.imagedit.app.domain.model.SceneType
import com.imagedit.app.domain.model.EnhancementSuggestion
import com.imagedit.app.ui.accessibility.AccessibilityUtils
import com.imagedit.app.ui.accessibility.enhancedAccessibility

/**
 * Card component that displays scene detection results with confidence indicators.
 */
@Composable
fun SceneDetectionCard(
    sceneAnalysis: SceneAnalysis?,
    isAnalyzing: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    onManualSceneSelect: ((SceneType) -> Unit)? = null,
    onApplyEnhancement: ((EnhancementSuggestion) -> Unit)? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Scene Detection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            when {
                error != null -> {
                    // Error state
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                
                sceneAnalysis != null -> {
                    // Announce scene detection results
                    val context = LocalContext.current
                    LaunchedEffect(sceneAnalysis) {
                        val sceneDescription = AccessibilityUtils.createSceneDetectionDescription(
                            getSceneTypeDisplayName(sceneAnalysis.sceneType),
                            sceneAnalysis.confidence
                        )
                        AccessibilityUtils.announceForAccessibility(
                            context,
                            sceneDescription,
                            1
                        )
                    }
                    
                    // Scene detection results
                    SceneDetectionResults(
                        sceneAnalysis = sceneAnalysis,
                        onManualSceneSelect = onManualSceneSelect,
                        onApplyEnhancement = onApplyEnhancement
                    )
                }
                
                isAnalyzing -> {
                    // Analyzing state
                    Text(
                        text = "Analyzing scene characteristics...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                else -> {
                    // No analysis yet
                    Text(
                        text = "Scene analysis will begin when photo loads",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SceneDetectionResults(
    sceneAnalysis: SceneAnalysis,
    onManualSceneSelect: ((SceneType) -> Unit)?,
    onApplyEnhancement: ((EnhancementSuggestion) -> Unit)?
) {
    Column {
        // Detected scene type with confidence and manual override
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = getSceneTypeIcon(sceneAnalysis.sceneType),
                        contentDescription = "Scene type: ${getSceneTypeDisplayName(sceneAnalysis.sceneType)}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = getSceneTypeDisplayName(sceneAnalysis.sceneType),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.semantics {
                                contentDescription = "Detected scene type: ${getSceneTypeDisplayName(sceneAnalysis.sceneType)}"
                            }
                        )
                        Text(
                            text = "Confidence: ${(sceneAnalysis.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics {
                                contentDescription = "Detection confidence: ${(sceneAnalysis.confidence * 100).toInt()} percent"
                            }
                        )
                        
                        // Show mixed scene indicator for low confidence
                        if (sceneAnalysis.confidence < 0.7f) {
                            Text(
                                text = "Mixed scene characteristics detected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    }
                }
                
                // Confidence indicator
                ConfidenceIndicator(confidence = sceneAnalysis.confidence)
            }
            
            // Manual scene override option
            if (onManualSceneSelect != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ManualSceneSelector(
                    currentScene = sceneAnalysis.sceneType,
                    onSceneSelect = onManualSceneSelect
                )
            }
        }
        
        // Enhancement suggestions if available
        if (sceneAnalysis.suggestedEnhancements.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Suggested Enhancements",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            // Show enhancement suggestion buttons
            sceneAnalysis.suggestedEnhancements.take(2).forEach { suggestion ->
                EnhancementSuggestionButton(
                    suggestion = suggestion,
                    onApply = { onApplyEnhancement?.invoke(suggestion) }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ConfidenceIndicator(confidence: Float) {
    val color = when {
        confidence >= 0.8f -> MaterialTheme.colorScheme.primary
        confidence >= 0.6f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
    )
}

private fun getSceneTypeIcon(sceneType: SceneType): ImageVector {
    return when (sceneType) {
        SceneType.PORTRAIT -> Icons.Default.Person
        SceneType.LANDSCAPE -> Icons.Default.Landscape
        SceneType.FOOD -> Icons.Default.Restaurant
        SceneType.NIGHT -> Icons.Default.NightlightRound
        SceneType.INDOOR -> Icons.Default.Home
        SceneType.MACRO -> Icons.Default.CenterFocusWeak
        SceneType.UNKNOWN -> Icons.Default.Help
    }
}

private fun getSceneTypeDisplayName(sceneType: SceneType): String {
    return when (sceneType) {
        SceneType.PORTRAIT -> "Portrait"
        SceneType.LANDSCAPE -> "Landscape"
        SceneType.FOOD -> "Food"
        SceneType.NIGHT -> "Night"
        SceneType.INDOOR -> "Indoor"
        SceneType.MACRO -> "Macro"
        SceneType.UNKNOWN -> "Unknown"
    }
}

@Composable
private fun EnhancementSuggestionButton(
    suggestion: EnhancementSuggestion,
    onApply: () -> Unit
) {
    OutlinedButton(
        onClick = onApply,
        modifier = Modifier
            .fillMaxWidth()
            .enhancedAccessibility(
                contentDescription = "Apply ${suggestion.type.name} enhancement with ${(suggestion.confidence * 100).toInt()} percent confidence",
                role = Role.Button,
                onClick = onApply
            ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = suggestion.type.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = suggestion.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${(suggestion.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSceneSelector(
    currentScene: SceneType,
    onSceneSelect: (SceneType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = "Override: ${getSceneTypeDisplayName(currentScene)}",
            onValueChange = { },
            readOnly = true,
            label = { Text("Manual Scene Selection") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SceneType.values().forEach { sceneType ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = getSceneTypeIcon(sceneType),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (sceneType == currentScene) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = getSceneTypeDisplayName(sceneType),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (sceneType == currentScene) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    },
                    onClick = {
                        onSceneSelect(sceneType)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}