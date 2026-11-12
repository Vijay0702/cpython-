package app.expirymate.android

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Item(
    val id: String,
    val name: String,
    val barcode: String?,
    val expiry: LocalDate,
    val status: String = ACTIVE,
    /** Hourly reminders are suppressed until this time (epoch milliseconds). */
    val snoozedUntil: Long? = null,
) {
    val isActive: Boolean get() = status == ACTIVE

    /** Whole days from [today] until the expiry day. Negative once expired. */
    fun daysLeft(today: LocalDate = LocalDate.now()): Int =
        ChronoUnit.DAYS.between(today, expiry).toInt()

    companion object {
        const val ACTIVE = "active"
        const val USED = "used"
        const val DISCARDED = "discarded"
    }
}

fun describeDaysLeft(days: Int): String = when {
    days < -1 -> "expired ${-days} days ago"
    days == -1 -> "expired yesterday"
    days == 0 -> "expires today"
    days == 1 -> "expires tomorrow"
    else -> "expires in $days days"
}
