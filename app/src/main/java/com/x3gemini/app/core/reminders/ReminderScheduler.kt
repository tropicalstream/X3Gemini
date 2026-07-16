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
        Log.i(TAG, "scheduled '${reminder.text.take(40)}' at ${Date(reminder.atMs)} exact=$canExact")
    }

    fun cancel(context: Context, reminderId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context, reminderId))
    }

    /** Re-register every stored reminder (boot / app start). Past-due ones fire now. */
    fun rescheduleAll(context: Context) {
        ReminderStore.init(context)
        val now = System.currentTimeMillis()
        ReminderStore.all().forEach { r ->
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
