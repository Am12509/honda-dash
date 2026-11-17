package app.hondadash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the adapter connection alive in the background, reconnects after the ignition or the head unit was off,
 * shows the connection state as a notification, plays the connect sound and sounds the alarms.
 */
class DashService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var lastState: Engine.State? = null
    private var lastText = ""
    private var lastConnects = 0
    private var lastBeep = 0L
    private var hotNotified = false

    private val wakeUp = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Bluetooth just came back on (head unit woke up): retry now instead of waiting for the next attempt
            val on = i.action != BluetoothAdapter.ACTION_STATE_CHANGED ||
                i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) == BluetoothAdapter.STATE_ON
            if (on && wants() && Dash.engine.state == Engine.State.WAITING) Dash.engine.retryNow()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null) {
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            })
        }
        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) {}
        startForeground(ID_STATUS, build())
        val f = IntentFilter()
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        f.addAction(Intent.ACTION_SCREEN_ON)
        try { registerReceiver(wakeUp, f) } catch (_: Exception) {}
        lastConnects = Dash.engine.connectCount
        ui.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = Dash.prefs(this)
        when (intent?.action) {
            ACTION_CONNECT -> {
                p.edit().putBoolean("want", true).apply()
                Dash.connect(this)
            }
            ACTION_DISCONNECT -> {
                p.edit().putBoolean("want", false).apply()
                Dash.disconnect(this)
            }
            ACTION_EXIT -> {
                p.edit().putBoolean("want", false).apply()
                Dash.disconnect(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (wants() && !Dash.active()) Dash.connect(this)
        }
        refresh(true)
        return START_STICKY
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        try { unregisterReceiver(wakeUp) } catch (_: Exception) {}
        tone?.release()
        super.onDestroy()
    }

    private fun wants(): Boolean = Dash.prefs(this).getBoolean("want", false)

    private val tick = object : Runnable {
        override fun run() {
            refresh(false)
            alarms()
            ui.postDelayed(this, 1000)
        }
    }

    private fun refresh(force: Boolean) {
        val e = Dash.engine
        if (e.connectCount != lastConnects) {
            lastConnects = e.connectCount
            if (Dash.prefs(this).getBoolean("snd_connect", true)) playConnect(tone)
        }
        val text = statusText()
        if (force || e.state != lastState || text != lastText) {
            lastState = e.state
            lastText = text
            getSystemService(NotificationManager::class.java)?.notify(ID_STATUS, build())
        }
    }

    private fun statusText(): String {
        val e = Dash.engine
        return when (e.state) {
            Engine.State.CONNECTED -> {
                val ect = fresh("ECT_F", 30000)
                "Connected" + (ect?.let { "   Coolant ${Math.round(it)} F" } ?: "")
            }
            Engine.State.CONNECTING -> "Connecting..."
            Engine.State.WAITING -> "Waiting for the car (ignition off?)"
            Engine.State.ERROR -> "Not connected (error)"
            Engine.State.IDLE -> if (wants()) "Starting..." else "Disconnected. Tap Connect to start."
        }
    }

    private fun fresh(key: String, maxAgeMs: Long): Double? {
        val st = Dash.engine.stamps[key] ?: return null
        if (System.currentTimeMillis() - st > maxAgeMs) return null
        return Dash.engine.values[key]?.takeIf { !it.isNaN() }
    }

    private fun build(): Notification {
        val e = Dash.engine
        val color = when (e.state) {
            Engine.State.CONNECTED -> Color.rgb(34, 197, 94)
            Engine.State.CONNECTING, Engine.State.WAITING -> Color.rgb(245, 158, 11)
            else -> Color.rgb(239, 68, 68)
        }
        val icon = when (e.state) {
            Engine.State.CONNECTED -> R.drawable.ic_stat_on
            Engine.State.CONNECTING, Engine.State.WAITING -> R.drawable.ic_stat_wait
            else -> R.drawable.ic_stat_off
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val running = Dash.active()
        val act = Intent(this, DashService::class.java).setAction(if (running) ACTION_DISCONNECT else ACTION_CONNECT)
        val actPi = PendingIntent.getService(this, 1, act, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val exitPi = PendingIntent.getService(this, 2, Intent(this, DashService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, CH_STATUS)
        return b.setSmallIcon(icon)
            .setColor(color)
            .setContentTitle("Honda Dash")
            .setContentText(statusText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (running) "Disconnect" else "Connect", actPi).build())
            .addAction(Notification.Action.Builder(null, "Exit", exitPi).build())
            .build()
    }

    private fun alarms() {
        val p = Dash.prefs(this)
        if (Dash.engine.state != Engine.State.CONNECTED) { hotNotified = false; return }
        val ect = fresh("ECT_F", 30000)
        val rpm = fresh("RPM_E", 4000)
        val hot = ect != null && ect > HOT_F
        val high = rpm != null && rpm > HIGH_RPM
        val now = System.currentTimeMillis()
        if ((hot || high) && p.getBoolean("snd_alarm", true) && now - lastBeep > (if (hot) 3000 else 1500)) {
            lastBeep = now
            playAlarm(tone)
        }
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (hot && !hotNotified) {
            hotNotified = true
            val b = Notification.Builder(this, CH_ALARM)
            nm.notify(ID_ALARM, b.setSmallIcon(R.drawable.ic_stat_off)
                .setColor(Color.rgb(239, 68, 68))
                .setContentTitle("Engine hot: ${Math.round(ect!!)} F")
                .setContentText("Coolant is above $HOT_F F. Ease off and watch the gauge.")
                .setAutoCancel(true)
                .build())
        } else if (!hot && hotNotified && ect != null && ect < HOT_F - 5) {
            hotNotified = false
            nm.cancel(ID_ALARM)
        }
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_ALARM = "alarm"
        const val ID_STATUS = 1
        const val ID_ALARM = 2
        const val ACTION_CONNECT = "app.hondadash.CONNECT"
        const val ACTION_DISCONNECT = "app.hondadash.DISCONNECT"
        const val ACTION_EXIT = "app.hondadash.EXIT"
        const val HOT_F = 225
        const val HIGH_RPM = 6350

        /** Two rising beeps. */
        fun playConnect(t: ToneGenerator?) {
            try { t?.startTone(ToneGenerator.TONE_PROP_ACK, 400) } catch (_: Exception) {}
        }

        /** One loud alert beep. */
        fun playAlarm(t: ToneGenerator?) {
            try { t?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600) } catch (_: Exception) {}
        }
    }
}

/** Starts the service after the head unit or phone boots, if the user was connected before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Dash.prefs(c).getBoolean("want", false)) Dash.startService(c)
    }
}
