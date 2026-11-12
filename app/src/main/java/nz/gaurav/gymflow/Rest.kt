package nz.gaurav.gymflow

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * The rest timer that keeps working when you leave the app:
 *  - an alarm fires exactly when rest ends (even with the screen off)
 *  - a notification shows a live countdown with -15 / +15 / Skip (only while resting)
 *  - when rest ends it plays your sound once, vibrates and shows "Let's go!"
 */
object Rest {
    const val CH_LIVE = "workout"
    const val CH_ALERT = "rest_alert"
    const val LIVE_ID = 1
    const val ALERT_ID = 2
    const val SOUND_FILE = "rest.wav"

    const val ACT_FIRE = "nz.gaurav.gymflow.REST_FIRE"
    const val ACT_PLUS = "nz.gaurav.gymflow.REST_PLUS"
    const val ACT_MINUS = "nz.gaurav.gymflow.REST_MINUS"
    const val ACT_SKIP = "nz.gaurav.gymflow.REST_SKIP"

    const val K_NAME = "workout"
    const val K_START = "start"
    const val K_END = "restEnd"
    const val K_LABEL = "label"
    const val K_VIBRATE = "vibrate"

    private var player: MediaPlayer? = null

    fun prefs(c: Context) = c.getSharedPreferences("gymflow", Context.MODE_PRIVATE)

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private fun channels(c: Context) {
        val nm = nm(c)
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, "Workout and rest timer", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Rest over", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)   // the app plays your chosen sound itself
                enableVibration(false) // and vibrates itself
            }
        )
    }

    // ---------- called from the web app ----------

    fun setWorkout(c: Context, name: String, start: Long) {
        prefs(c).edit().putString(K_NAME, name).putLong(K_START, start).apply()
    }

    /** Only one caller can end a rest: the first to arrive wins, everyone after does nothing. */
    @Synchronized
    private fun claim(c: Context): Boolean {
        val p = prefs(c)
        if (p.getLong(K_END, 0L) == 0L) return false
        p.edit().putLong(K_END, 0L).commit()
        return true
    }

    /** The app's own clock reached zero first (app open): ring once and cancel the alarm. */
    fun firedByApp(c: Context) {
        if (!claim(c)) return
        alarmManager(c).cancel(firePending(c))
        playSound(c)
        if (prefs(c).getBoolean(K_VIBRATE, true)) vibrate(c)
        show(c)
    }

    fun clearWorkout(c: Context) {
        prefs(c).edit().remove(K_NAME).remove(K_START).putLong(K_END, 0L).apply()
        alarmManager(c).cancel(firePending(c))
        nm(c).cancel(LIVE_ID)
        nm(c).cancel(ALERT_ID)
    }

    fun start(c: Context, endAt: Long, label: String) {
        prefs(c).edit().putLong(K_END, endAt).putString(K_LABEL, label).apply()
        schedule(c, endAt)
        show(c)
    }

    fun cancel(c: Context) {
        prefs(c).edit().putLong(K_END, 0L).apply()
        alarmManager(c).cancel(firePending(c))
        show(c)
    }

    fun adjust(c: Context, deltaMs: Long) {
        val p = prefs(c)
        val end = p.getLong(K_END, 0L)
        if (end == 0L) return
        val now = System.currentTimeMillis()
        start(c, maxOf(now + 1000L, end + deltaMs), p.getString(K_LABEL, "") ?: "")
    }

    // ---------- alarm ----------

    private fun alarmManager(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun firePending(c: Context): PendingIntent =
        PendingIntent.getBroadcast(
            c, 10, Intent(c, RestReceiver::class.java).setAction(ACT_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun schedule(c: Context, at: Long) {
        val am = alarmManager(c)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, firePending(c))
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, firePending(c))
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, firePending(c))
        }
    }

    /** Rest is over. */
    fun fire(c: Context) {
        val p = prefs(c)
        val label = p.getString(K_LABEL, "") ?: ""
        if (!claim(c)) return // skipped, or the app already rang for this rest

        playSound(c)
        if (p.getBoolean(K_VIBRATE, true)) vibrate(c)
        show(c)

        val act = MainActivity.instance
        if (MainActivity.foreground && act != null) {
            act.js("nativeRestDone()")
            return
        }

        // In another app: a heads-up "Let's go!". Screen off or locked: opens the full-screen alert.
        channels(c)
        val open = Intent(c, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ALERT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            c, 20, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Let's go!")
            .setContentText(label)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000L)
            .build()
        try { nm(c).notify(ALERT_ID, n) } catch (e: SecurityException) { }
        act?.js("nativeRestDone()")
    }

    // ---------- the ongoing notification ----------

    private fun actionPending(c: Context, action: String, req: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, req, Intent(c, RestReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun show(c: Context) {
        val p = prefs(c)
        val name = p.getString(K_NAME, null) ?: "Workout"
        val now = System.currentTimeMillis()
        val end = p.getLong(K_END, 0L)
        // Only show a notification while a rest countdown is running.
        if (end <= now) {
            nm(c).cancel(LIVE_ID)
            return
        }
        channels(c)
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(c, CH_LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentTitle("Resting · $name")
            .setContentText(p.getString(K_LABEL, "") ?: "")
            .setWhen(end)
            .setChronometerCountDown(true)
            .addAction(0, "−15 s", actionPending(c, ACT_MINUS, 11))
            .addAction(0, "+15 s", actionPending(c, ACT_PLUS, 12))
            .addAction(0, "Skip", actionPending(c, ACT_SKIP, 13))
        try { nm(c).notify(LIVE_ID, b.build()) } catch (e: SecurityException) { }
    }

    // ---------- sound and vibration ----------

    private fun playSound(c: Context) {
        try {
            player?.release()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA) // plays through earbuds when connected
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            val f = File(c.filesDir, SOUND_FILE)
            if (f.exists()) mp.setDataSource(f.absolutePath)
            else mp.setDataSource(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) { }
    }

    private fun vibrate(c: Context) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                c.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 90, 180), -1))
        } catch (e: Exception) { }
    }
}

/** Receives the rest alarm and the notification buttons. */
class RestReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Rest.ACT_FIRE -> {
                // Keep the app alive long enough to finish playing the sound.
                val pending = goAsync()
                Rest.fire(c)
                Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 6000)
            }
            Rest.ACT_PLUS -> { Rest.adjust(c, 15_000L); MainActivity.instance?.js("nativeRestSync()") }
            Rest.ACT_MINUS -> { Rest.adjust(c, -15_000L); MainActivity.instance?.js("nativeRestSync()") }
            Rest.ACT_SKIP -> { Rest.cancel(c); MainActivity.instance?.js("nativeRestSync()") }
        }
    }
}
