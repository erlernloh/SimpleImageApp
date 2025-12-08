package com.imagedit.app.ui.editor.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

/**
 * Enhanced progress animations for smart photo enhancement operations
 */

@Composable
fun SmartEnhancementProgressAnimation(
    progress: Float,
    operationType: String,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(300)) + scaleIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(300)) + scaleOut(animationSpec = tween(300))
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Animated circular progress indicator
                AnimatedCircularProgress(
                    progress = progress,
                    modifier = Modifier.size(80.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Operation type with typewriter animation
                TypewriterText(
                    text = operationType,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Progress percentage
                AnimatedProgressText(progress = progress)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Animated progress bar
                AnimatedLinearProgress(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                )
            }
        }
    }
}

@Composable
fun AnimatedCircularProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 8f,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = 800,
            easing = FastOutSlowInEasing
        ),
        label = "CircularProgress"
    )
    
    val infiniteTransition = rememberInfiniteTransition(label = "InfiniteRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )
    
    Canvas(modifier = modifier) {
        val canvasSize = size.minDimension
        val radius = (canvasSize - strokeWidth) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        
        // Background circle
        drawCircle(
            color = backgroundColor,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth)
        )
        
        // Progress arc
        if (animatedProgress > 0f) {
            drawArc(
                color = progressColor,
                startAngle = -90f + rotation,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = Offset(
                    center.x - radius,
                    center.y - radius
                ),
                size = Size(radius * 2, radius * 2),
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round
                )
            )
        }
        
        // Glowing effect
        if (animatedProgress > 0.5f) {
            drawCircle(
                color = progressColor.copy(alpha = 0.3f),
                radius = radius + strokeWidth / 2,
                center = center,
                style = Stroke(width = strokeWidth / 2)
            )
        }
    }
}

@Composable
fun TypewriterText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    typingSpeed: Int = 50
) {
    var displayedText by remember { mutableStateOf("") }
    
    LaunchedEffect(text) {
        displayedText = ""
        text.forEachIndexed { index, _ ->
            kotlinx.coroutines.delay(typingSpeed.toLong())
            displayedText = text.substring(0, index + 1)
        }
    }
    
    Text(
        text = displayedText,
        style = style,
        modifier = modifier
    )
}

@Composable
fun AnimatedProgressText(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing
        ),
        label = "ProgressText"
    )
    
    val percentage = (animatedProgress * 100).toInt()
    
    Text(
        text = "$percentage%",
        style = MaterialTheme.typography.bodyLarge.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier
    )
}

@Composable
fun AnimatedLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = 600,
            easing = FastOutSlowInEasing
        ),
        label = "LinearProgress"
    )
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            progressColor,
                            progressColor.copy(alpha = 0.8f)
                        )
                    ),
                    shape = RoundedCornerShape(50)
                )
        )
    }
}

@Composable
fun PulsingLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PulsingAnimation")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Scale"
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Alpha"
    )
    
    Box(
        modifier = modifier
            .scale(scale)
            .background(
                color = color.copy(alpha = alpha),
                shape = CircleShape
            )
    )
}

@Composable
fun WaveLoadingAnimation(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    waveCount: Int = 3
) {
    val infiniteTransition = rememberInfiniteTransition(label = "WaveAnimation")
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(waveCount) { index ->
            val animationDelay = index * 200
            
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 1200,
                        delayMillis = animationDelay,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "WaveScale$index"
            )
            
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(scaleX = 1f, scaleY = scale)
                    .background(
                        color = color,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
fun ShimmerLoadingEffect(
    modifier: Modifier = Modifier,
    isLoading: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "ShimmerTransition")
    
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerTranslate"
    )
    
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    )
    
    val brush = if (isLoading) {
        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset(translateAnim - 200f, translateAnim - 200f),
            end = Offset(translateAnim, translateAnim)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Transparent),
            start = Offset.Zero,
            end = Offset.Zero
        )
    }
    
    Box(
        modifier = modifier
            .background(brush = brush)
            .clip(RoundedCornerShape(8.dp))
    )
}

@Composable
fun SuccessCheckmarkAnimation(
    isVisible: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.Green,
    size: Float = 60f
) {
    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "CheckmarkScale"
    )
    
    val strokeWidth = with(LocalDensity.current) { 4.dp.toPx() }
    
    Canvas(
        modifier = modifier
            .size(size.dp)
            .scale(scale)
    ) {
        val canvasSize = this.size.minDimension
        val center = Offset(canvasSize / 2f, canvasSize / 2f)
        val radius = canvasSize / 2f - strokeWidth
        
        // Circle background
        drawCircle(
            color = color,
            radius = radius,
            center = center
        )
        
        // Checkmark
        val checkmarkPath = Path().apply {
            val checkSize = radius * 0.6f
            val startX = center.x - checkSize * 0.3f
            val startY = center.y
            val midX = center.x - checkSize * 0.1f
            val midY = center.y + checkSize * 0.3f
            val endX = center.x + checkSize * 0.4f
            val endY = center.y - checkSize * 0.2f
            
            moveTo(startX, startY)
            lineTo(midX, midY)
            lineTo(endX, endY)
        }
        
        drawPath(
            path = checkmarkPath,
            color = Color.White,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}