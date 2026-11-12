package app.expirymate.android

import java.time.LocalDateTime

data class ReminderSettings(
    val digestHour: Int = 9,
    val digestMinute: Int = 0,
    /** Hourly reminders on the day before expiry run from this hour... */
    val hourlyStart: Int = 8,
    /** ...to this hour, inclusive. */
    val hourlyEnd: Int = 21,
)

/** The next moment a reminder is due, and which kinds are due at that moment. */
data class NextReminder(val at: LocalDateTime, val digest: Boolean, val hourly: Boolean)

/**
 * Works out when the next reminder should fire.
 *
 * - Every day: one summary at the chosen time.
 * - The day before an item expires: one reminder per hour for that item,
 *   until the user marks it as used or thrown away.
 *
 * Only the next reminder is ever scheduled; when it fires, the one after is planned.
 */
object ReminderPlanner {
    fun next(activeItems: List<Item>, settings: ReminderSettings, now: LocalDateTime): NextReminder? {
        if (activeItems.isEmpty()) return null
        val today = now.toLocalDate()

        var digestAt = today.atTime(settings.digestHour, settings.digestMinute)
        if (!digestAt.isAfter(now)) digestAt = digestAt.plusDays(1)

        val hourlyAt = activeItems
            .map { it.expiry.minusDays(1) }
            .filter { !it.isBefore(today) }
            .distinct()
            .sorted()
            .firstNotNullOfOrNull { day ->
                (settings.hourlyStart..settings.hourlyEnd)
                    .map { day.atTime(it, 0) }
                    .firstOrNull { it.isAfter(now) }
            }

        val at = if (hourlyAt != null && hourlyAt.isBefore(digestAt)) hourlyAt else digestAt
        return NextReminder(at, digest = at == digestAt, hourly = at == hourlyAt)
    }
}
