package com.imagedit.app.ui.editor.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex

/**
 * Interactive onboarding tutorials for smart photo enhancement features
 */

data class TutorialStep(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val targetPosition: Offset? = null,
    val highlightRadius: Float = 100f,
    val action: String = "Next"
)

@Composable
fun SmartEnhancementOnboarding(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tutorialSteps = remember {
        listOf(
            TutorialStep(
                title = "Welcome to Smart Enhancement!",
                description = "Discover powerful AI-driven photo enhancement tools that make your photos look professional with just a few taps.",
                icon = Icons.Default.AutoAwesome
            ),
            TutorialStep(
                title = "One-Tap Smart Enhancement",
                description = "Tap the Smart Enhance button to automatically improve exposure, contrast, and colors using advanced algorithms.",
                icon = Icons.Default.AutoFixHigh
            ),
            TutorialStep(
                title = "Portrait Mode",
                description = "Perfect for selfies and portraits. Automatically detects and enhances skin tones, eyes, and facial features.",
                icon = Icons.Default.Face
            ),
            TutorialStep(
                title = "Landscape Enhancement",
                description = "Enhance outdoor photos with sky and foliage optimization, natural color grading, and clarity improvements.",
                icon = Icons.Default.Landscape
            ),
            TutorialStep(
                title = "Healing Tool",
                description = "Remove unwanted objects, spots, or blemishes by painting over them. The tool intelligently fills the area.",
                icon = Icons.Default.Brush
            ),
            TutorialStep(
                title = "Before & After Comparison",
                description = "Hold and drag to compare your original photo with the enhanced version at any time.",
                icon = Icons.Default.Compare
            )
        )
    }
    
    var currentStep by remember { mutableStateOf(0) }
    
    if (isVisible) {
        OnboardingOverlay(
            step = tutorialSteps[currentStep],
            stepNumber = currentStep + 1,
            totalSteps = tutorialSteps.size,
            onNext = {
                if (currentStep < tutorialSteps.size - 1) {
                    currentStep++
                } else {
                    onComplete()
                }
            },
            onSkip = onDismiss,
            onPrevious = if (currentStep > 0) {
                { currentStep-- }
            } else null
        )
    }
}

@Composable
fun OnboardingOverlay(
    step: TutorialStep,
    stepNumber: Int,
    totalSteps: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onPrevious: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onSkip,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable { /* Prevent clicks from passing through */ }
        ) {
            // Spotlight effect for target position
            step.targetPosition?.let { position ->
                SpotlightEffect(
                    center = position,
                    radius = step.highlightRadius,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Tutorial content card
            TutorialCard(
                step = step,
                stepNumber = stepNumber,
                totalSteps = totalSteps,
                onNext = onNext,
                onSkip = onSkip,
                onPrevious = onPrevious,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
fun TutorialCard(
    step: TutorialStep,
    stepNumber: Int,
    totalSteps: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onPrevious: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "CardScale"
    )
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Progress indicator
            TutorialProgressIndicator(
                currentStep = stepNumber,
                totalSteps = totalSteps,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Icon with animation
            AnimatedTutorialIcon(
                icon = step.icon,
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Title
            Text(
                text = step.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Description
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 24.sp
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous button
                if (onPrevious != null) {
                    TextButton(
                        onClick = onPrevious,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Previous")
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                
                // Skip button
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Skip")
                }
                
                // Next/Finish button
                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (stepNumber == totalSteps) "Get Started" else step.action,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (stepNumber < totalSteps) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TutorialProgressIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            val isActive = index + 1 <= currentStep
            val isNext = index + 1 == currentStep + 1
            
            val scale by animateFloatAsState(
                targetValue = when {
                    isActive -> 1.2f
                    isNext -> 1.1f
                    else -> 1f
                },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "IndicatorScale$index"
            )
            
            val color by animateColorAsState(
                targetValue = when {
                    isActive -> MaterialTheme.colorScheme.primary
                    isNext -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.outline
                },
                animationSpec = tween(300),
                label = "IndicatorColor$index"
            )
            
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(scale)
                    .background(
                        color = color,
                        shape = CircleShape
                    )
            )
            
            if (index < totalSteps - 1) {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

@Composable
fun AnimatedTutorialIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "IconAnimation")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IconScale"
    )
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IconRotation"
    )
    
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .scale(scale)
            .graphicsLayer {
                rotationZ = rotation
            }
    )
}

@Composable
fun SpotlightEffect(
    center: Offset,
    radius: Float,
    modifier: Modifier = Modifier
) {
    val animatedRadius by animateFloatAsState(
        targetValue = radius,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "SpotlightRadius"
    )
    
    Canvas(modifier = modifier) {
        drawSpotlight(center, animatedRadius)
    }
}

private fun DrawScope.drawSpotlight(center: Offset, radius: Float) {
    // Create a radial gradient for the spotlight effect
    val gradient = androidx.compose.ui.graphics.RadialGradientShader(
        center = center,
        radius = radius,
        colors = listOf(
            Color.Transparent,
            Color.Black.copy(alpha = 0.3f),
            Color.Black.copy(alpha = 0.7f)
        ),
        colorStops = listOf(0f, 0.7f, 1f)
    )
    
    drawRect(
        brush = androidx.compose.ui.graphics.Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = 0.3f),
                Color.Black.copy(alpha = 0.7f)
            ),
            center = center,
            radius = radius
        )
    )
}

@Composable
fun FeatureHighlight(
    targetPosition: Offset,
    title: String,
    description: String,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { onDismiss() }
        ) {
            // Highlight circle
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.1f),
                    radius = 80f,
                    center = targetPosition,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                )
            }
            
            // Tooltip
            Card(
                modifier = Modifier
                    .padding(16.dp)
                    .offset(
                        x = with(LocalDensity.current) { (targetPosition.x - 100).toDp() },
                        y = with(LocalDensity.current) { (targetPosition.y + 120).toDp() }
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Got it")
                    }
                }
            }
        }
    }
}

@Composable
fun QuickTip(
    message: String,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { it }
        ) + fadeOut()
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}