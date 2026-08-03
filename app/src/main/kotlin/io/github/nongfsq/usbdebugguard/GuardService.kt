package io.github.nongfsq.usbdebugguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

data class NotificationState(
    val status: GuardStatus,
    val circuitOpen: Boolean,
    val failureCount: Int,
    val lastOutcome: RootOutcome?,
)

class NotificationStateTracker {
    private var last: NotificationState? = null

    fun shouldNotify(next: NotificationState): Boolean {
        if (last == next) return false
        last = next
        return true
    }
}

class GuardService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private lateinit var stateMachine: GuardStateMachine
    private lateinit var adbObserver: ContentObserver
    private val notificationTracker = NotificationStateTracker()
    private val pendingForceRepair = AtomicBoolean(false)

    @Volatile
    private var destroyed = false

    @Volatile
    private var acceptingEvents = false

    private val evaluate = Runnable {
        val forceRepair = pendingForceRepair.getAndSet(false)
        val report = stateMachine.tick(forceRepair)
        if (!destroyed) updateNotification(report)
        if (!shouldRemainRunning()) stopSelf()
    }

    private val systemStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (acceptingEvents) scheduleEvaluation(forceRepair = false)
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

        val initial = GuardReport(
            status = GuardStatus.Unknown,
            probe = UsbAdbState(usbConnected = false, adbEnabled = false),
            circuit = RootRuntime.get(this).circuit(),
        )
        startForeground(NOTIFICATION_ID, notification(initial.status))
        notificationTracker.shouldNotify(initial.toNotificationState())
        registerStateObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acceptingEvents = true
        when (intent?.action) {
            ACTION_STOP -> {
                workerHandler.removeCallbacks(evaluate)
                workerHandler.post {
                    val report = stateMachine.stop()
                    if (!destroyed) updateNotification(report)
                    if (!shouldRemainRunning()) stopSelfResult(startId)
                }
                return START_STICKY
            }

            ACTION_RETRY_ROOT -> {
                workerHandler.post {
                    val report = stateMachine.retryRoot()
                    if (!destroyed) updateNotification(report)
                    if (report.result?.ok == true &&
                        (GuardPrefs.serviceEnabled(this) || GuardPrefs.isGuarded(this))
                    ) {
                        scheduleEvaluation(forceRepair = false)
                    } else if (!shouldRemainRunning()) {
                        stopSelfResult(startId)
                    }
                }
            }

            ACTION_LOCK_NOW -> {
                workerHandler.post {
                    val report = stateMachine.lockNow()
                    if (!destroyed) updateNotification(report)
                    if (!shouldRemainRunning()) stopSelfResult(startId)
                }
            }

            ACTION_REFRESH -> scheduleEvaluation(forceRepair = true)

            ACTION_START -> {
                GuardPrefs.setServiceEnabled(this, true)
                scheduleEvaluation(forceRepair = false)
            }

            else -> {
                if (shouldRemainRunning()) scheduleEvaluation(forceRepair = false)
                else stopSelfResult(startId)
            }
        }
        return if (shouldRemainRunning()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        acceptingEvents = false
        unregisterStateObservers()
        workerHandler.removeCallbacksAndMessages(null)
        RootRuntime.shutdown()
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleEvaluation(forceRepair: Boolean) {
        if (destroyed) return
        if (forceRepair) pendingForceRepair.set(true)
        workerHandler.removeCallbacks(evaluate)
        workerHandler.post(evaluate)
    }

    private fun registerStateObservers() {
        val filter = IntentFilter().apply {
            addAction(AndroidUsbAdbStateReader.ACTION_USB_STATE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(systemStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(systemStateReceiver, filter)
        }

        adbObserver = object : ContentObserver(workerHandler) {
            override fun onChange(selfChange: Boolean) {
                if (acceptingEvents) scheduleEvaluation(forceRepair = false)
            }
        }
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
            false,
            adbObserver,
        )
    }

    private fun unregisterStateObservers() {
        runCatching { unregisterReceiver(systemStateReceiver) }
        if (::adbObserver.isInitialized) {
            runCatching { contentResolver.unregisterContentObserver(adbObserver) }
        }
    }

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
            GuardStatus.RootUnavailable -> R.string.notification_root_unavailable
            GuardStatus.CircuitOpen -> R.string.notification_circuit_open
            GuardStatus.RestoreFailed -> R.string.notification_restore_failed
            GuardStatus.Unknown -> R.string.notification_monitoring
        }
    )

    private fun updateNotification(report: GuardReport) {
        if (!notificationTracker.shouldNotify(report.toNotificationState())) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(report.status))
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun shouldRemainRunning(): Boolean =
        GuardPrefs.serviceEnabled(this) ||
            GuardPrefs.isGuarded(this) ||
            GuardPrefs.snapshotPending(this) ||
            GuardPrefs.stopPending(this)

    companion object {
        const val ACTION_START = "io.github.nongfsq.usbdebugguard.action.START"
        const val ACTION_STOP = "io.github.nongfsq.usbdebugguard.action.STOP"
        const val ACTION_REFRESH = "io.github.nongfsq.usbdebugguard.action.REFRESH"
        const val ACTION_RETRY_ROOT = "io.github.nongfsq.usbdebugguard.action.RETRY_ROOT"
        const val ACTION_LOCK_NOW = "io.github.nongfsq.usbdebugguard.action.LOCK_NOW"
        private const val CHANNEL_ID = "protection"
        private const val NOTIFICATION_ID = 1001
    }
}

private fun GuardReport.toNotificationState() = NotificationState(
    status = status,
    circuitOpen = circuit.open,
    failureCount = circuit.failureCount,
    lastOutcome = result?.outcome ?: circuit.lastOutcome,
)
