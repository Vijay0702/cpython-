package app.expirymate.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ExpiryDateParserTest {
    private val today = LocalDate.of(2026, 11, 12)

    private fun parse(text: String, dayFirst: Boolean = true) = ExpiryDateParser.parse(text, today, dayFirst)

    @Test fun numericDayFirst() = assertEquals(LocalDate.of(2027, 1, 12), parse("EXP 12/01/2027"))
    @Test fun numericMonthFirst() = assertEquals(LocalDate.of(2027, 12, 1), parse("12/01/2027", dayFirst = false))
    @Test fun unambiguousDayWinsOverLocale() = assertEquals(LocalDate.of(2027, 6, 30), parse("06/30/27"))
    @Test fun isoDate() = assertEquals(LocalDate.of(2027, 1, 12), parse("BB 2027-01-12"))
    @Test fun monthName() = assertEquals(LocalDate.of(2027, 1, 12), parse("12 JAN 2027"))
    @Test fun monthNameCompact() = assertEquals(LocalDate.of(2027, 3, 5), parse("use by 05-Mar-27"))
    @Test fun monthNameAmerican() = assertEquals(LocalDate.of(2027, 1, 27), parse("JAN 27, 2027"))
    @Test fun monthOnlyMeansEndOfMonth() = assertEquals(LocalDate.of(2027, 2, 28), parse("BEST BEFORE FEB 2027"))
    @Test fun expiryBeatsManufacture() = assertEquals(LocalDate.of(2027, 1, 31), parse("MFG 01/2026 EXP 01/2027"))
    @Test fun expiryBeatsLaterUnlabelledOrder() = assertEquals(LocalDate.of(2026, 12, 1), parse("EXP 01/12/2026 MFD 01/12/2027"))
    @Test fun ordinaryWordsAreNotMonths() = assertNull(parse("DECAF 27G"))
    @Test fun monthAndYearSeparatedBySpace() = assertEquals(
        LocalDate.of(2027, 11, 30),
        parse("Vitamin B12 250 µg Food Supplement, 60 Tablets\nBest Before End:\n11 2027 BH 180109"),
    )
    @Test fun spacedFullDateIsNotReadAsMonthOnly() = assertEquals(LocalDate.of(2027, 1, 12), parse("12 01 2027"))
    @Test fun monthNameDayYearIsNotReadAsMonthOnly() = assertEquals(LocalDate.of(2027, 1, 11), parse("JAN 11 2027"))
    @Test fun shelfLifeFromManufactureMonth() = assertEquals(
        LocalDate.of(2028, 1, 31),
        parse("Regd. 7/2021. (K) Marico Ltd\nMfd.:\nBatch No.:\n02/2025\nGG480,1B\nUse Before 36 months\nfrom the date of manufacturing."),
    )
    @Test fun shelfLifeFromFullManufactureDate() = assertEquals(
        LocalDate.of(2027, 3, 14),
        parse("PKD 15/09/2026 Best before 6 months from packaging"),
    )
    @Test fun labelledExpiryBeatsShelfLife() = assertEquals(
        LocalDate.of(2027, 5, 31),
        parse("MFG 06/2026 EXP 05/2027 best before 12 months from manufacture"),
    )
    @Test fun shelfLifeWithoutManufactureDate() = assertNull(parse("Use before 36 months from the date of manufacturing"))
    @Test fun noDate() = assertNull(parse("Semi skimmed milk 2 litres"))
}

class ReminderPlannerTest {
    private val settings = ReminderSettings()

    private fun item(expiry: LocalDate) = Item(id = expiry.toString(), name = "Milk", barcode = null, expiry = expiry)

    @Test fun nothingTrackedMeansNoReminder() =
        assertNull(ReminderPlanner.next(emptyList(), settings, LocalDateTime.of(2026, 11, 12, 10, 30)))

    @Test fun digestIsNextWhenNothingExpiresSoon() {
        val next = ReminderPlanner.next(listOf(item(LocalDate.of(2026, 12, 1))), settings, LocalDateTime.of(2026, 11, 12, 10, 30))
        assertEquals(NextReminder(LocalDateTime.of(2026, 11, 13, 9, 0), digest = true, hourly = false), next)
    }

    @Test fun hourlyOnTheDayBeforeExpiry() {
        val next = ReminderPlanner.next(listOf(item(LocalDate.of(2026, 11, 13))), settings, LocalDateTime.of(2026, 11, 12, 10, 30))
        assertEquals(NextReminder(LocalDateTime.of(2026, 11, 12, 11, 0), digest = false, hourly = true), next)
    }

    @Test fun digestAndHourlyCanCoincide() {
        val next = ReminderPlanner.next(listOf(item(LocalDate.of(2026, 11, 13))), settings, LocalDateTime.of(2026, 11, 12, 8, 30))
        assertEquals(NextReminder(LocalDateTime.of(2026, 11, 12, 9, 0), digest = true, hourly = true), next)
    }

    @Test fun afterTheHourlyWindowFallsBackToDigest() {
        val next = ReminderPlanner.next(listOf(item(LocalDate.of(2026, 11, 13))), settings, LocalDateTime.of(2026, 11, 12, 21, 30))
        assertEquals(NextReminder(LocalDateTime.of(2026, 11, 13, 9, 0), digest = true, hourly = false), next)
    }
}
