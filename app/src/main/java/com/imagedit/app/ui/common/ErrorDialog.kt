package com.imagedit.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Reusable error dialog component with retry functionality
 */
@Composable
fun ErrorDialog(
    error: String?,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    title: String = "Error",
    icon: ImageVector = Icons.Default.Error
) {
    if (error != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                if (onRetry != null) {
                    Button(
                        onClick = {
                            onDismiss()
                            onRetry()
                        }
                    ) {
                        Text("Retry")
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            },
            dismissButton = {
                if (onRetry != null) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

/**
 * User-friendly error message generator
 */
object ErrorMessageHelper {
    fun getErrorMessage(exception: Exception): String {
        return when (exception) {
            is java.io.IOException -> "Network error. Please check your connection and try again."
            is SecurityException -> "Permission denied. Please grant the required permissions in Settings."
            is OutOfMemoryError -> "Not enough memory. Please close other apps and try again."
            is IllegalArgumentException -> "Invalid input. Please check your data and try again."
            is IllegalStateException -> "Operation not allowed in current state. Please try again."
            else -> exception.message ?: "An unexpected error occurred. Please try again."
        }
    }
    
    fun getErrorMessage(exception: Exception, operation: String): String {
        val baseMessage = getErrorMessage(exception)
        return "Failed to $operation: $baseMessage"
    }
}
