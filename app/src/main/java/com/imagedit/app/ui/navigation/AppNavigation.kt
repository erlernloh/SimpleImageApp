package com.imagedit.app.ui.navigation

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.imagedit.app.ui.camera.CameraScreen
import com.imagedit.app.ui.gallery.GalleryScreen
import com.imagedit.app.ui.editor.PhotoEditorScreen
import com.imagedit.app.ui.editor.BatchEditorScreen
import com.imagedit.app.ui.viewer.PhotoViewerScreen
import com.imagedit.app.ui.settings.SettingsScreen
import com.imagedit.app.ui.settings.ModelManagementScreen
import com.imagedit.app.ultradetail.UltraDetailScreen
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }
    val currentRoute = navController.currentBackStackEntryFlow.collectAsState(initial = navController.currentBackStackEntry)
    
    Scaffold { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            NavHost(
                navController = navController,
                startDestination = "camera"
            ) {
            composable("camera") {
                CameraScreen(
                    onNavigateToGallery = {
                        navController.navigate("gallery")
                    },
                    onPhotoTaken = { uri ->
                        val encodedUri = URLEncoder.encode(uri, StandardCharsets.UTF_8.toString())
                        navController.navigate("editor/$encodedUri")
                    },
                    onBatchEdit = { uris ->
                        // Encode all URIs and join with separator for batch editing
                        val encodedUris = uris.map { 
                            URLEncoder.encode(it, StandardCharsets.UTF_8.toString()) 
                        }.joinToString("|")
                        navController.navigate("batch_editor/$encodedUris")
                    },
                    onNavigateToUltraDetail = {
                        navController.navigate("ultra_detail")
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings")
                    }
                )
            }
        
        // Gallery route - supports optional exportUri parameter
        composable(
            route = "gallery?exportUri={exportUri}",
            arguments = listOf(
                androidx.navigation.navArgument("exportUri") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val encodedExportUri = backStackEntry.arguments?.getString("exportUri")
            val exportUri = encodedExportUri?.takeIf { it != "null" }?.let {
                URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
            }
            GalleryScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onPhotoSelected = { uri ->
                    val encodedUri = URLEncoder.encode(uri, StandardCharsets.UTF_8.toString())
                    navController.navigate("editor/$encodedUri")
                },
                onPhotoView = { uri ->
                    val encodedUri = URLEncoder.encode(uri, StandardCharsets.UTF_8.toString())
                    navController.navigate("viewer/$encodedUri")
                },
                onBatchEdit = { uris ->
                    // Encode all URIs and join with separator
                    val encodedUris = uris.map { 
                        URLEncoder.encode(it, StandardCharsets.UTF_8.toString()) 
                    }.joinToString("|")
                    navController.navigate("batch_editor/$encodedUris")
                },
                exportPhotoUri = exportUri
            )
        }
        
        composable("viewer/{photoUri}") { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("photoUri") ?: ""
            val photoUri = URLDecoder.decode(encodedUri, StandardCharsets.UTF_8.toString())
            PhotoViewerScreen(
                photoUri = photoUri,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onEditPhoto = { uri ->
                    val encodedUri = URLEncoder.encode(uri, StandardCharsets.UTF_8.toString())
                    navController.navigate("editor/$encodedUri")
                },
                onExportPhoto = { uri ->
                    // Navigate back to gallery with export intent
                    val encodedUri = URLEncoder.encode(uri, StandardCharsets.UTF_8.toString())
                    navController.navigate("gallery?exportUri=$encodedUri") {
                        popUpTo("gallery") { inclusive = true }
                    }
                }
            )
        }
        
        composable("editor/{photoUri}") { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("photoUri") ?: ""
            val photoUri = URLDecoder.decode(encodedUri, StandardCharsets.UTF_8.toString())
            PhotoEditorScreen(
                photoUri = photoUri,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }
        
        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToModelManagement = {
                    navController.navigate("model_management")
                }
            )
        }
        
        composable("model_management") {
            ModelManagementScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable("batch_editor/{photoUris}") { backStackEntry ->
            val encodedUris = backStackEntry.arguments?.getString("photoUris") ?: ""
            val photoUris = encodedUris.split("|").map { 
                URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) 
            }
            BatchEditorScreen(
                photoUris = photoUris,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable("ultra_detail") {
            UltraDetailScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onImageSaved = {
                    // Image is saved by the screen, navigate to gallery to see it
                    navController.navigate("gallery") {
                        popUpTo("camera") { inclusive = false }
                    }
                }
            )
        }
        }
            
            // Exit confirmation dialog
            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text("Exit App") },
                    text = { Text("Are you sure you want to exit the app?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showExitDialog = false
                                (context as? Activity)?.finishAffinity()
                            }
                        ) {
                            Text("Exit")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showExitDialog = false }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
