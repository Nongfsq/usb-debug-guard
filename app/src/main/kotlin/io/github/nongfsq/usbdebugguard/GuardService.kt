package io.github.nongfsq.usbdebugguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class GuardService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private lateinit var stateMachine: GuardStateMachine

    private val tick = object : Runnable {
        override fun run() {
            val report = stateMachine.tick()
            updateNotification(report.status)
            workerHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("USB-Debug-Guard")
        workerThread.start()
        workerHandler = Handler(workerThread.looper)
        stateMachine = GuardStateMachine(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification(GuardStatus.Unknown))
        workerHandler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                GuardPrefs.setServiceEnabled(this, false)
                workerHandler.removeCallbacks(tick)
                workerHandler.post {
                    val report = stateMachine.stop()
                    updateNotification(report.status)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                workerHandler.post {
                    val report = stateMachine.tick(forceRepair = true)
                    updateNotification(report.status)
                }
            }
            else -> {
                GuardPrefs.setServiceEnabled(this, true)
                workerHandler.post {
                    val report = stateMachine.tick(forceRepair = true)
                    updateNotification(report.status)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        workerHandler.removeCallbacks(tick)
        if (GuardPrefs.isGuarded(this)) {
            workerHandler.post {
                stateMachine.stop()
                workerThread.quitSafely()
            }
        } else {
            workerThread.quitSafely()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(status: GuardStatus): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, GuardService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(notificationText(status))
            .setContentIntent(openPendingIntent)
            .setOngoing(status == GuardStatus.Protected)
            .addAction(R.drawable.ic_notification, getString(R.string.button_turn_off), stopPendingIntent)
            .build()
    }

    private fun notificationText(status: GuardStatus): String = getString(
        when (status) {
            GuardStatus.Protected -> R.string.notification_guard_active
            GuardStatus.Idle -> R.string.notification_restored
            GuardStatus.WaitingUsb -> R.string.notification_waiting_usb
            GuardStatus.WaitingAdb -> R.string.notification_waiting_adb
            GuardStatus.RootRequired -> R.string.notification_root_required
            GuardStatus.RestoreFailed -> R.string.notification_restore_failed
            GuardStatus.Unknown -> R.string.notification_monitoring
        }
    )

    private fun updateNotification(status: GuardStatus) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(status))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "io.github.nongfsq.usbdebugguard.action.START"
        const val ACTION_STOP = "io.github.nongfsq.usbdebugguard.action.STOP"
        const val ACTION_REFRESH = "io.github.nongfsq.usbdebugguard.action.REFRESH"
        private const val CHANNEL_ID = "protection"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
