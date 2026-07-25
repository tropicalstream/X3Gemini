package com.x3gemini.app.core.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.x3gemini.app.R
import com.x3gemini.app.core.bridge.HudPinStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AlarmManager plumbing for [ReminderStore]. Delivery on the glasses is
 * two-channel so it can't be missed:
 *   1. a system notification (the "push notification" the feature asks for)
 *   2. a ⏰ post-it pin on the HUD board — glasses-native, stays visible
 *      until the user removes it.
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    private const val CHANNEL_ID = "x3gemini_reminders"

    fun schedule(context: Context, reminder: ReminderStore.Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = firePendingIntent(context, reminder.id)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMs, pi)
        } else {
            // No exact-alarm permission: a 1-minute window is fine for reminders.
            am.setWindow(AlarmManager.RTC_WAKEUP, reminder.atMs, 60_000L, pi)
        }
        postCountdownPin(context, reminder)
        Log.i(TAG, "scheduled '${reminder.text.take(40)}' at ${Date(reminder.atMs)} exact=$canExact")
    }

    fun cancel(context: Context, reminderId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context, reminderId))
        removeCountdownPin(context, reminderId)
    }

    /** Re-register every stored reminder (boot / app start). Past-due ones fire now. */
    fun rescheduleAll(context: Context) {
        ReminderStore.init(context)
        val now = System.currentTimeMillis()
        val pending = ReminderStore.all()
        // Sweep countdown chips whose reminder no longer exists (killed by
        // a crash between the two stores, or an old build's pins) — the
        // reminder list is the truth, the chips are derived from it.
        runCatching {
            HudPinStore.init(context)
            val live = pending.map { it.id }.toSet()
            HudPinStore.all()
                .filter { it.type == HudPinStore.TYPE_COUNTDOWN && it.id !in live }
                .forEach { HudPinStore.remove(it.id) }
        }
        pending.forEach { r ->
            if (r.atMs <= now) {
                // Missed while powered off — deliver immediately.
                deliver(context, r)
            } else {
                schedule(context, r)
            }
        }
    }

    /** Fire path: notification + HUD pin, then remove or roll daily. */
    fun deliver(context: Context, reminder: ReminderStore.Reminder) {
        notify(context, reminder.text)
        // The countdown chip has served its purpose — drop it before the
        // ⏰ post-it lands so the board never shows both for one reminder.
        // (A daily repeat gets a fresh countdown from schedule() below.)
        removeCountdownPin(context, reminder.id)
        postHudPin(context, reminder.text)
        if (reminder.repeatDaily) {
            var next = reminder.atMs
            val now = System.currentTimeMillis()
            while (next <= now) next += 24L * 60 * 60 * 1000
            val rolled = reminder.copy(atMs = next)
            ReminderStore.update(rolled)
            schedule(context, rolled)
        } else {
            ReminderStore.remove(reminder.id)
        }
    }

    private fun notify(context: Context, text: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "X3Gemini voice-set reminders" }
                )
            }
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Reminder")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(text.hashCode(), n)
        }.onFailure { Log.w(TAG, "notification failed: ${it.message}") }
    }

    private fun postHudPin(context: Context, text: String) {
        runCatching {
            HudPinStore.init(context)
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            HudPinStore.add(
                HudPinStore.HudPin(
                    type = HudPinStore.TYPE_NOTE,
                    label = "⏰ $time",
                    payload = text.take(280)
                )
            )
        }.onFailure { Log.w(TAG, "HUD pin failed: ${it.message}") }
    }

    /**
     * Live countdown chip for a still-pending reminder. The pin id IS the
     * reminder id — that's the whole bookkeeping: fire and cancel just
     * remove by the id they already have.
     *
     * Failing to pin is never fatal: the alarm and the notification are
     * the real delivery channels, the chip is a convenience (it can also
     * legitimately fail when the board is at MAX_PINS).
     */
    private fun postCountdownPin(context: Context, reminder: ReminderStore.Reminder) {
        runCatching {
            HudPinStore.init(context)
            HudPinStore.add(
                HudPinStore.HudPin(
                    id = reminder.id,
                    type = HudPinStore.TYPE_COUNTDOWN,
                    label = reminder.text.take(40),
                    payload = reminder.text.take(80),
                    dueAtMs = reminder.atMs
                )
            )
        }.onFailure { Log.w(TAG, "countdown pin failed: ${it.message}") }
    }

    private fun removeCountdownPin(context: Context, reminderId: String) {
        runCatching {
            HudPinStore.init(context)
            HudPinStore.remove(reminderId)
        }.onFailure { Log.w(TAG, "countdown pin removal failed: ${it.message}") }
    }

    private fun firePendingIntent(context: Context, reminderId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_ID, reminderId)
        return PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Alarm fire + boot re-registration. */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.x3gemini.app.REMINDER_FIRE"
        const val EXTRA_ID = "reminder_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                ReminderStore.init(context)
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val reminder = ReminderStore.get(id) ?: return
                ReminderScheduler.deliver(context, reminder)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                ReminderScheduler.rescheduleAll(context)
        }
    }
}
