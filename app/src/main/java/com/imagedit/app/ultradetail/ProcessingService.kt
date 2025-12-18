/**
 * ProcessingService.kt - Foreground service for long-running Ultra Detail processing
 * 
 * Prevents the app from being killed during ULTRA mode processing which can take
 * several minutes on lower-end devices.
 */

package com.imagedit.app.ultradetail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat

private const val TAG = "ProcessingService"
private const val CHANNEL_ID = "ultra_detail_processing"
private const val CHANNEL_ID_COMPLETE = "ultra_detail_complete"
private const val NOTIFICATION_ID = 1001
private const val NOTIFICATION_ID_COMPLETE = 1002

/**
 * Foreground service to keep the app alive during long processing
 */
class ProcessingService : Service() {
    
    private val binder = LocalBinder()
    private var notificationManager: NotificationManager? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): ProcessingService = this@ProcessingService
    }
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d(TAG, "ProcessingService created")
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Processing Ultra Detail...", 0)
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "ProcessingService started in foreground")
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ProcessingService destroyed")
    }
    
    /**
     * Update the notification with current progress
     */
    fun updateProgress(message: String, progress: Int) {
        val notification = createNotification(message, progress)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Stop the foreground service
     */
    fun stopProcessing() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Progress channel (low importance, no sound)
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                "Ultra Detail Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress during Ultra Detail image processing"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(progressChannel)
            
            // Completion channel (default importance, with sound)
            val completeChannel = NotificationChannel(
                CHANNEL_ID_COMPLETE,
                "Ultra Detail Complete",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when Ultra Detail processing is complete"
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(completeChannel)
        }
    }
    
    private fun createNotification(message: String, progress: Int): Notification {
        // Intent to open the app when notification is tapped
        // Use getLaunchIntentForPackage with null safety
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, Class.forName("${packageName}.MainActivity"))
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ultra Detail+")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }
    
    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ProcessingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, ProcessingService::class.java))
        }
        
        /**
         * Show a completion notification with optional preview thumbnail.
         * This is shown after processing completes, even if the service is stopped.
         */
        fun showCompletionNotification(
            context: Context,
            savedUri: Uri?,
            previewBitmap: Bitmap?,
            processingTimeMs: Long,
            resolution: String
        ) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Create channel if needed (for when called without service)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID_COMPLETE,
                    "Ultra Detail Complete",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifies when Ultra Detail processing is complete"
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            // Intent to open the app
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent()
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            // Format time
            val timeText = when {
                processingTimeMs < 1000 -> "${processingTimeMs}ms"
                processingTimeMs < 60000 -> String.format("%.1fs", processingTimeMs / 1000.0)
                else -> String.format("%.1fm", processingTimeMs / 60000.0)
            }
            
            val builder = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETE)
                .setContentTitle("✨ Ultra Detail+ Complete")
                .setContentText("$resolution image ready • Processed in $timeText")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
            
            // Add preview thumbnail if available
            previewBitmap?.let { bitmap ->
                // Scale down for notification (max 256px)
                val maxSize = 256
                val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height, 1f)
                val thumbWidth = (bitmap.width * scale).toInt()
                val thumbHeight = (bitmap.height * scale).toInt()
                val thumbnail = if (scale < 1f) {
                    Bitmap.createScaledBitmap(bitmap, thumbWidth, thumbHeight, true)
                } else {
                    bitmap
                }
                
                builder.setLargeIcon(thumbnail)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(thumbnail)
                            .bigLargeIcon(null as Bitmap?) // Hide large icon in expanded view
                    )
            }
            
            // Add share action if we have a saved URI
            savedUri?.let { uri ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val sharePendingIntent = PendingIntent.getActivity(
                    context,
                    1,
                    Intent.createChooser(shareIntent, "Share Ultra Detail+ image"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(
                    android.R.drawable.ic_menu_share,
                    "Share",
                    sharePendingIntent
                )
            }
            
            notificationManager.notify(NOTIFICATION_ID_COMPLETE, builder.build())
        }
    }
}
