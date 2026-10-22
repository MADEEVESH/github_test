package dev.offlinechat.offlinechat.mesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.offlinechat.offlinechat.MainActivity

/** The service is started only by the user's visible Start action. */
class MeshForegroundService : Service() {
    private var runtime: MeshRuntime? = null

    companion object {
        const val ACTION_START = "dev.offlinechat.mesh.START"
        const val ACTION_STOP = "dev.offlinechat.mesh.STOP"
        private const val SERVICE_CHANNEL = "offlinechat_mesh_service"
        private const val MESSAGE_CHANNEL = "offlinechat_mesh_messages"
        private const val SERVICE_NOTIFICATION = 4201
        private const val MESSAGE_NOTIFICATION = 4202

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshForegroundService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }

        fun notifyIncoming(context: Context) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            createChannels(context)
            val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("OfflineChat")
                .setContentText("New encrypted message. Open OfflineChat to read it.")
                .setContentIntent(openApp(context)).setAutoCancel(true).build()
            context.getSystemService(NotificationManager::class.java).notify(MESSAGE_NOTIFICATION, notification)
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 4200,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "Bluetooth mesh service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the foreground Bluetooth service and its user Stop action."
            })
            manager.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL, "Encrypted messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Generic incoming message notifications; plaintext is not displayed."
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        val stopIntent = PendingIntent.getService(this, 4203,
            Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("OfflineChat mesh service")
            .setContentText("Bluetooth mesh requested. Open the app for connection status.")
            .setContentIntent(openApp(this)).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop mesh", stopIntent).build()
        try {
            startForeground(SERVICE_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            val active = MeshRuntime.get(this).also { runtime = it }
            active.setForeground(true)
            active.startTransport()
        } catch (error: Exception) {
            val reason = "Could not start the Bluetooth foreground service: ${error.message ?: error.javaClass.simpleName}"
            Log.e("OfflineChatMesh", reason, error)
            runCatching {
                val active = runtime ?: MeshRuntime.get(this).also { runtime = it }
                active.reportServiceFailure(reason)
                active.setForeground(false)
            }
            stopSelf()
        }
        // Force-stop/background restrictions are not bypassed. A restart needs user action.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runtime?.stopTransport()
        runtime?.setForeground(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
