package com.imagedit.app.ui.accessibility

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.os.Bundle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Voice control support for smart photo enhancement features.
 * Provides voice commands for common enhancement operations.
 */
class VoiceControlSupport(private val context: Context) {
    
    private val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private val commandChannel = Channel<VoiceCommand>(Channel.UNLIMITED)
    val commands = commandChannel.receiveAsFlow()
    
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            // Handle recognition errors
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            matches?.firstOrNull()?.let { spokenText ->
                parseVoiceCommand(spokenText)?.let { command ->
                    commandChannel.trySend(command)
                }
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    init {
        speechRecognizer.setRecognitionListener(recognitionListener)
    }
    
    /**
     * Start listening for voice commands.
     */
    fun startListening() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer.startListening(intent)
        }
    }
    
    /**
     * Stop listening for voice commands.
     */
    fun stopListening() {
        speechRecognizer.stopListening()
    }
    
    /**
     * Parse spoken text into voice commands.
     */
    private fun parseVoiceCommand(spokenText: String): VoiceCommand? {
        val text = spokenText.lowercase().trim()
        
        return when {
            // Smart enhancement commands
            text.contains("smart enhance") || text.contains("auto enhance") -> 
                VoiceCommand.SmartEnhance
            
            text.contains("enhance portrait") || text.contains("portrait mode") -> 
                VoiceCommand.EnhancePortrait
            
            text.contains("enhance landscape") || text.contains("landscape mode") -> 
                VoiceCommand.EnhanceLandscape
            
            // Healing tool commands
            text.contains("healing tool") || text.contains("heal") -> 
                VoiceCommand.ActivateHealingTool
            
            text.contains("apply healing") -> 
                VoiceCommand.ApplyHealing
            
            text.contains("undo") -> 
                VoiceCommand.Undo
            
            text.contains("clear") -> 
                VoiceCommand.Clear
            
            // Comparison commands
            text.contains("show before") || text.contains("before after") -> 
                VoiceCommand.ShowBeforeAfter
            
            text.contains("hide comparison") -> 
                VoiceCommand.HideComparison
            
            // Brush size commands
            text.contains("increase brush") || text.contains("bigger brush") -> 
                VoiceCommand.IncreaseBrushSize
            
            text.contains("decrease brush") || text.contains("smaller brush") -> 
                VoiceCommand.DecreaseBrushSize
            
            // Intensity commands
            text.contains("increase intensity") -> 
                VoiceCommand.IncreaseIntensity
            
            text.contains("decrease intensity") -> 
                VoiceCommand.DecreaseIntensity
            
            // Save and navigation commands
            text.contains("save photo") || text.contains("save image") -> 
                VoiceCommand.SavePhoto
            
            text.contains("reset") || text.contains("start over") -> 
                VoiceCommand.Reset
            
            else -> null
        }
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        speechRecognizer.destroy()
        commandChannel.close()
    }
}

/**
 * Voice commands supported by the enhancement system.
 */
sealed class VoiceCommand {
    object SmartEnhance : VoiceCommand()
    object EnhancePortrait : VoiceCommand()
    object EnhanceLandscape : VoiceCommand()
    object ActivateHealingTool : VoiceCommand()
    object ApplyHealing : VoiceCommand()
    object Undo : VoiceCommand()
    object Clear : VoiceCommand()
    object ShowBeforeAfter : VoiceCommand()
    object HideComparison : VoiceCommand()
    object IncreaseBrushSize : VoiceCommand()
    object DecreaseBrushSize : VoiceCommand()
    object IncreaseIntensity : VoiceCommand()
    object DecreaseIntensity : VoiceCommand()
    object SavePhoto : VoiceCommand()
    object Reset : VoiceCommand()
}

/**
 * Composable for integrating voice control support.
 */
@Composable
fun VoiceControlIntegration(
    onCommand: (VoiceCommand) -> Unit
) {
    val context = LocalContext.current
    val voiceControl = remember { VoiceControlSupport(context) }
    
    LaunchedEffect(voiceControl) {
        voiceControl.commands.collect { command ->
            onCommand(command)
        }
    }
    
    DisposableEffect(voiceControl) {
        onDispose {
            voiceControl.destroy()
        }
    }
}

/**
 * Helper function to get voice command descriptions for accessibility.
 */
fun getVoiceCommandDescription(command: VoiceCommand): String {
    return when (command) {
        VoiceCommand.SmartEnhance -> "Say 'smart enhance' to apply automatic enhancement"
        VoiceCommand.EnhancePortrait -> "Say 'enhance portrait' to apply portrait enhancement"
        VoiceCommand.EnhanceLandscape -> "Say 'enhance landscape' to apply landscape enhancement"
        VoiceCommand.ActivateHealingTool -> "Say 'healing tool' to activate the healing tool"
        VoiceCommand.ApplyHealing -> "Say 'apply healing' to process healing strokes"
        VoiceCommand.Undo -> "Say 'undo' to undo the last action"
        VoiceCommand.Clear -> "Say 'clear' to clear all strokes"
        VoiceCommand.ShowBeforeAfter -> "Say 'show before after' to compare original and enhanced"
        VoiceCommand.HideComparison -> "Say 'hide comparison' to hide the comparison view"
        VoiceCommand.IncreaseBrushSize -> "Say 'increase brush' to make the brush larger"
        VoiceCommand.DecreaseBrushSize -> "Say 'decrease brush' to make the brush smaller"
        VoiceCommand.IncreaseIntensity -> "Say 'increase intensity' to strengthen the effect"
        VoiceCommand.DecreaseIntensity -> "Say 'decrease intensity' to weaken the effect"
        VoiceCommand.SavePhoto -> "Say 'save photo' to save your changes"
        VoiceCommand.Reset -> "Say 'reset' to return to the original photo"
    }
}