package io.github.hkaaf.echoport.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import io.github.hkaaf.echoport.R
import io.github.hkaaf.echoport.ui.MainActivity

/** Foreground service that keeps audio flowing while the screen is off or the app is closed. */
class EchoService : Service() {
    private var types = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Hub.init(this)
        when (intent?.action) {
            ACTION_SERVER -> {
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                val system = data != null
                goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    (if (system) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0))
                val projection = data?.let {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), it)
                }
                Hub.launchServer(projection)
            }
            ACTION_PLAYER -> {
                goForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                Hub.launchPlayer()
            }
            ACTION_IDLE -> if (!Hub.isActive) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(newTypes: Int) {
        types = types or newTypes
        val fgTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) types else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), fgTypes)
        acquireLocks()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "echoport:wifi").apply { acquire() }
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "echoport:audio").apply { acquire() }
        }
    }

    override fun onDestroy() {
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SERVER = "echoport.SERVER"
        const val ACTION_PLAYER = "echoport.PLAYER"
        const val ACTION_IDLE = "echoport.IDLE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "echoport"
        private const val NOTIFICATION_ID = 1
    }
}
