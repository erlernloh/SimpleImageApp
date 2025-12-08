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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

private const val TAG = "ProcessingService"
private const val CHANNEL_ID = "ultra_detail_processing"
private const val NOTIFICATION_ID = 1001

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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ultra Detail Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress during Ultra Detail image processing"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(message: String, progress: Int): Notification {
        // Intent to open the app when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
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
    }
}
