package app.expirymate.android

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Schedules reminder alarms and shows the notifications. */
object Reminders {
    private const val CHANNEL = "expiry_reminders"
    private const val DIGEST_ID = 1
    private const val PREFS = "settings"
    private const val SNOOZE_HOURS = 3

    const val EXTRA_DIGEST = "digest"
    const val EXTRA_HOURLY = "hourly"
    const val EXTRA_ITEM = "item"
    const val ACTION_USED = "app.expirymate.android.USED"
    const val ACTION_DISCARDED = "app.expirymate.android.DISCARDED"
    const val ACTION_SNOOZE = "app.expirymate.android.SNOOZE"
    const val SNOOZE_MILLIS = SNOOZE_HOURS * 60 * 60 * 1000L

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel(context: Context) {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL, "Expiry reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun settings(context: Context): ReminderSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = ReminderSettings()
        return ReminderSettings(
            digestHour = prefs.getInt("digestHour", defaults.digestHour),
            digestMinute = prefs.getInt("digestMinute", defaults.digestMinute),
            hourlyStart = prefs.getInt("hourlyStart", defaults.hourlyStart),
            hourlyEnd = prefs.getInt("hourlyEnd", defaults.hourlyEnd),
        )
    }

    fun saveSettings(context: Context, settings: ReminderSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("digestHour", settings.digestHour)
            .putInt("digestMinute", settings.digestMinute)
            .putInt("hourlyStart", settings.hourlyStart)
            .putInt("hourlyEnd", settings.hourlyEnd)
            .apply()
        reschedule(context)
    }

    /** Sets one alarm for the next reminder, or clears it when nothing is being tracked. */
    fun reschedule(context: Context) {
        ItemStore.load(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ReminderReceiver::class.java)
        val active = ItemStore.items.value.filter { it.isActive }
        val next = ReminderPlanner.next(active, settings(context), LocalDateTime.now())

        if (next == null) {
            alarms.cancel(alarmIntent(context, intent))
            return
        }
        intent.putExtra(EXTRA_DIGEST, next.digest).putExtra(EXTRA_HOURLY, next.hourly)
        val millis = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarmIntent(context, intent))
    }

    private fun alarmIntent(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun postDigest(context: Context, active: List<Item>, today: LocalDate) {
        val sorted = active.sortedBy { it.expiry }
        val lines = sorted.take(4).map { "${it.name} ${describeDaysLeft(it.daysLeft(today))}" }
        val extra = sorted.size - lines.size
        val body = lines.joinToString("\n") + if (extra > 0) "\n+ $extra more" else ""
        val title = if (sorted.size == 1) "1 item to keep an eye on" else "${sorted.size} items to keep an eye on"

        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        manager(context).notify(DIGEST_ID, notification)
    }

    fun postItem(context: Context, item: Item) {
        fun action(name: String, label: String): Notification.Action {
            val intent = Intent(context, ActionReceiver::class.java).setAction(name).putExtra(EXTRA_ITEM, item.id)
            val pending = PendingIntent.getBroadcast(
                context, item.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Action.Builder(null, label, pending).build()
        }

        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${item.name} expires tomorrow")
            .setContentText("Use it today. Mark it as used or thrown away to stop these reminders.")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .addAction(action(ACTION_USED, "Used it"))
            .addAction(action(ACTION_DISCARDED, "Threw it away"))
            .addAction(action(ACTION_SNOOZE, "Snooze $SNOOZE_HOURS hours"))
            .build()
        manager(context).notify(item.id.hashCode(), notification)
    }

    fun cancelItem(context: Context, itemId: String) {
        manager(context).cancel(itemId.hashCode())
    }
}

/** Runs when a reminder alarm fires, and after the phone restarts. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ItemStore.load(context)
        Reminders.ensureChannel(context)
        val active = ItemStore.items.value.filter { it.isActive }
        val today = LocalDate.now()

        if (intent.getBooleanExtra(Reminders.EXTRA_DIGEST, false) && active.isNotEmpty()) {
            Reminders.postDigest(context, active, today)
        }
        if (intent.getBooleanExtra(Reminders.EXTRA_HOURLY, false)) {
            val now = System.currentTimeMillis()
            active
                .filter { it.expiry == today.plusDays(1) && (it.snoozedUntil ?: 0L) <= now }
                .forEach { Reminders.postItem(context, it) }
        }
        Reminders.reschedule(context)
    }
}

/** Runs when the user taps a button on an item's reminder. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(Reminders.EXTRA_ITEM) ?: return
        ItemStore.update(context) { items ->
            items.map { item ->
                if (item.id != itemId) return@map item
                when (intent.action) {
                    Reminders.ACTION_USED -> item.copy(status = Item.USED)
                    Reminders.ACTION_DISCARDED -> item.copy(status = Item.DISCARDED)
                    Reminders.ACTION_SNOOZE -> item.copy(snoozedUntil = System.currentTimeMillis() + Reminders.SNOOZE_MILLIS)
                    else -> item
                }
            }
        }
        Reminders.cancelItem(context, itemId)
        Reminders.reschedule(context)
    }
}
