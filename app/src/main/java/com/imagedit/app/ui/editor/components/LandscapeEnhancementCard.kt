package com.imagedit.app.ui.editor.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imagedit.app.domain.model.*
import com.imagedit.app.ui.accessibility.AccessibilityUtils
import com.imagedit.app.ui.accessibility.enhancedAccessibility
import com.imagedit.app.ui.accessibility.accessibleSlider

/**
 * Main landscape enhancement card component that contains all landscape-specific controls.
 */
@Composable
fun LandscapeEnhancementCard(
    landscapeAnalysis: LandscapeAnalysis?,
    parameters: LandscapeEnhancementParameters,
    isProcessing: Boolean,
    isApplied: Boolean,
    isShowingBeforeAfter: Boolean,
    onApplyEnhancement: () -> Unit,
    onParametersChange: (LandscapeEnhancementParameters) -> Unit,
    onToggleBeforeAfter: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    EditorCategoryCard(
        title = "Landscape Enhancement",
        isExpanded = isExpanded,
        onToggle = { isExpanded = !isExpanded },
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Announce landscape enhancement state changes
            val context = LocalContext.current
            LaunchedEffect(isApplied) {
                if (isApplied) {
                    AccessibilityUtils.announceForAccessibility(
                        context,
                        "Landscape enhancement applied successfully",
                        1
                    )
                }
            }
            
            // Landscape Enhancement toggle button
            Button(
                onClick = onApplyEnhancement,
                enabled = !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .enhancedAccessibility(
                        contentDescription = if (isApplied) 
                            "Landscape enhancement applied, tap to adjust settings" 
                        else 
                            "Apply landscape enhancement to optimize outdoor photo elements",
                        role = Role.Button,
                        onClick = onApplyEnhancement,
                        stateDescription = if (isProcessing) "Processing" else if (isApplied) "Applied" else "Ready",
                        disabled = isProcessing
                    ),
                colors = if (isApplied) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = if (isApplied) Icons.Default.Landscape else Icons.Default.Landscape,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (isApplied) "Landscape Enhanced" else "Enhance Landscape"
                )
            }
            
            // Landscape analysis info
            if (landscapeAnalysis != null && landscapeAnalysis.isLandscapeScene) {
                LandscapePreviewOverlay(
                    analysis = landscapeAnalysis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Enhancement controls (when landscape enhancement is active)
            if (isApplied) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Overall intensity control
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Overall Intensity",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${(parameters.overallIntensity * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Slider(
                            value = parameters.overallIntensity,
                            onValueChange = { 
                                onParametersChange(parameters.copy(overallIntensity = it))
                            },
                            valueRange = 0f..1f,
                            steps = 19,
                            enabled = !isProcessing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .accessibleSlider(
                                    value = parameters.overallIntensity,
                                    valueRange = 0f..1f,
                                    parameterName = "Overall landscape enhancement intensity",
                                    onValueChange = { 
                                        onParametersChange(parameters.copy(overallIntensity = it))
                                    },
                                    steps = 19,
                                    unit = "percent"
                                )
                        )
                    }
                    
                    // Sky enhancement controls
                    SkyEnhancementSliders(
                        settings = parameters.skySettings,
                        onSettingsChange = { 
                            onParametersChange(parameters.copy(skySettings = it))
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Foliage enhancement controls
                    FoliageControls(
                        settings = parameters.foliageSettings,
                        onSettingsChange = { 
                            onParametersChange(parameters.copy(foliageSettings = it))
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Color grading controls
                    ColorGradingPanel(
                        parameters = parameters.colorGrading,
                        onParametersChange = { 
                            onParametersChange(parameters.copy(colorGrading = it))
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Additional settings
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Use Region Detection",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = parameters.useRegionDetection,
                                onCheckedChange = { 
                                    onParametersChange(parameters.copy(useRegionDetection = it))
                                },
                                enabled = !isProcessing,
                                modifier = Modifier.enhancedAccessibility(
                                    contentDescription = "Use automatic region detection for selective landscape enhancement",
                                    role = Role.Switch,
                                    stateDescription = if (parameters.useRegionDetection) "Enabled" else "Disabled",
                                    disabled = isProcessing
                                )
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Preserve Color Harmony",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = parameters.preserveColorHarmony,
                                onCheckedChange = { 
                                    onParametersChange(parameters.copy(preserveColorHarmony = it))
                                },
                                enabled = !isProcessing,
                                modifier = Modifier.enhancedAccessibility(
                                    contentDescription = "Preserve natural color harmony during landscape enhancement",
                                    role = Role.Switch,
                                    stateDescription = if (parameters.preserveColorHarmony) "Enabled" else "Disabled",
                                    disabled = isProcessing
                                )
                            )
                        }
                    }
                }
            }
            
            // Information text
            Text(
                text = if (landscapeAnalysis?.isLandscapeScene == true) {
                    "Enhance outdoor photos with sky optimization, foliage enhancement, and natural color grading. Detected landscape elements will be enhanced selectively."
                } else {
                    "Enhance outdoor photos with sky optimization, foliage enhancement, and natural color grading. Works best with landscape and nature photography."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            
            // Before/After toggle (when landscape enhancement is applied)
            if (isApplied) {
                OutlinedButton(
                    onClick = onToggleBeforeAfter,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (isShowingBeforeAfter) 
                            Icons.Default.VisibilityOff 
                        else 
                            Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isShowingBeforeAfter) 
                            "Hide Comparison" 
                        else 
                            "Show Before/After"
                    )
                }
            }
        }
    }
}