package ai.androclaw.core

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.androclaw.MainActivity
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Foreground service that keeps the OpenClaw agent process alive.
 *
 * Runs at low priority with a persistent notification.
 * The agent itself runs in AgentService coroutines on Dispatchers.IO.
 */
class OpenClawForegroundService : Service() {

    private val agentService: AgentService by inject()

    override fun onCreate() {
        super.onCreate()
        Timber.d("OpenClawForegroundService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY   // Restart automatically if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("OpenClawForegroundService destroyed")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AndroClaw Agent",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "AndroClaw AI assistant is active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroClaw")
            .setContentText("AI assistant is ready")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID      = "androclaw_agent"
        const val NOTIFICATION_ID = 1001
    }
}
