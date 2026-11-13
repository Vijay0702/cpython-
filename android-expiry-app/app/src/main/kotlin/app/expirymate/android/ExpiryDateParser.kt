package app.expirymate.android

import android.text.format.DateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Finds the most likely expiry date in text read off a package.
 *
 * Handles numeric dates (12/01/2027, 12-01-27, 2027-01-12, 01/2027) and
 * month-name dates (12 JAN 2027, JAN 2027, JAN 12, 2027). When several dates
 * are printed, one that follows "EXP", "BEST BEFORE" or "USE BY" wins, one
 * that follows "MFG" or "PKD" loses, and otherwise the latest date wins.
 *
 * Also handles a shelf life instead of a date ("Use before 36 months from
 * manufacture"): the expiry is then calculated from the manufacture date.
 */
/** [note] explains a calculated date, for example "36 months from 1 Feb 2025". */
data class ParsedExpiry(val date: LocalDate, val note: String? = null)

object ExpiryDateParser {

    fun parse(
        text: String,
        today: LocalDate = LocalDate.now(),
        dayFirst: Boolean = localeIsDayFirst(),
    ): LocalDate? = parseDetailed(text, today, dayFirst)?.date

    fun parseDetailed(
        text: String,
        today: LocalDate = LocalDate.now(),
        dayFirst: Boolean = localeIsDayFirst(),
    ): ParsedExpiry? {
        val raw = text.uppercase(Locale.ROOT)

        // "Use before 36 months from manufacture": the printed date is then the
        // manufacture date. The phrase is blanked out so its words and numbers
        // are not mistaken for an expiry label or a date.
        val shelfLife = shelfLifeRegex.find(raw)
        val upper = if (shelfLife == null) raw else raw.replaceRange(shelfLife.range, " ".repeat(shelfLife.value.length))
        val oldestYear = today.year - if (shelfLife == null) 2 else 10

        val candidates = mutableListOf<Candidate>()
        for (pattern in patterns) {
            for (match in pattern.regex.findAll(upper)) {
                val parts = pattern.build(match.groupValues.drop(1), dayFirst) ?: continue
                if (parts.year !in oldestYear..(today.year + 15)) continue
                val date = makeDate(parts) ?: continue
                val start = LocalDate.of(parts.year, parts.month, parts.day ?: 1)

                val context = upper.substring(maxOf(0, match.range.first - 24), match.range.first)
                candidates += Candidate(date, start, score(context), match.range, parts.day != null)
            }
        }

        // "12 01 2027" also contains "01 2027". Where a month-only reading overlaps
        // a full date, the full date is the right one.
        val fullDates = candidates.filter { it.hasDay }
        val remaining = candidates.filter { c ->
            c.hasDay || fullDates.none { it.range.first <= c.range.last && c.range.first <= it.range.last }
        }
        val best = remaining.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.date })

        // An explicitly labelled expiry date beats a calculated one.
        if (shelfLife != null && (best == null || best.score < 1)) {
            val made = remaining
                .filter { !it.start.isAfter(today) }
                .maxWithOrNull(compareBy<Candidate> { -it.score }.thenBy { it.start })
            if (made != null) {
                val amount = shelfLife.groupValues[1].toLong()
                val end = when (shelfLife.groupValues[2]) {
                    "DAY" -> made.start.plusDays(amount)
                    "WEEK" -> made.start.plusWeeks(amount)
                    "MONTH" -> made.start.plusMonths(amount)
                    else -> made.start.plusYears(amount)
                }
                val unit = shelfLife.groupValues[2].lowercase(Locale.ROOT) + if (amount == 1L) "" else "s"
                val madeOn = made.start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                // "Before" 36 months: the last good day is the day before.
                return ParsedExpiry(end.minusDays(1), "$amount $unit from $madeOn")
            }
        }
        return best?.let { ParsedExpiry(it.date) }
    }

    private val shelfLifeRegex =
        Regex("(?:(?:BEST|USE|CONSUME)\\s+)?(?:BEFORE|WITHIN)\\s*:?\\s*(\\d{1,3})\\s*(DAY|WEEK|MONTH|YEAR)S?")

    /** [start] differs from [date] only for month-only dates: first versus last day of the month. */
    private class Candidate(
        val date: LocalDate,
        val start: LocalDate,
        val score: Int,
        val range: IntRange,
        val hasDay: Boolean,
    )

    /** True when the user's region writes the day before the month. */
    fun localeIsDayFirst(): Boolean {
        val format = DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMy")
        val day = format.indexOf('d')
        val month = format.indexOf('M')
        return day < 0 || month < 0 || day < month
    }

    /** [day] is null for "end of the month" (packages printed as MM/YYYY). */
    private class Parts(val year: Int, val month: Int, val day: Int?)

    private class Pattern(pattern: String, val build: (List<String>, Boolean) -> Parts?) {
        val regex = Regex(pattern)
    }

    private val monthPrefixes = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    // Full names are spelled out so that words like "DECAF" or "MARKET" are not read as months.
    private const val MONTHS = "JAN(?:UARY)?|FEB(?:RUARY)?|MAR(?:CH)?|APR(?:IL)?|MAY|JUNE?|JULY?|AUG(?:UST)?" +
        "|SEP(?:T(?:EMBER)?)?|OCT(?:OBER)?|NOV(?:EMBER)?|DEC(?:EMBER)?"
    private const val SEP = "[\\s./-]"

    // Lookarounds stop a pattern matching inside a longer run of digits or letters.
    private const val LEAD = "(?<![0-9A-Z])"
    private const val TRAIL = "(?![0-9A-Z])"

    private val patterns = listOf(
        // 2027-01-12
        Pattern("$LEAD(20\\d{2})$SEP(\\d{1,2})$SEP(\\d{1,2})$TRAIL") { g, _ ->
            Parts(g[0].toInt(), g[1].toInt(), g[2].toInt())
        },
        // 12/01/2027, 12-01-27
        Pattern("$LEAD(\\d{1,2})$SEP(\\d{1,2})$SEP(\\d{4}|\\d{2})$TRAIL") { g, dayFirst ->
            val a = g[0].toInt()
            val b = g[1].toInt()
            val aIsDay = when {
                a > 12 -> true
                b > 12 -> false
                else -> dayFirst
            }
            Parts(fullYear(g[2].toInt()), if (aIsDay) b else a, if (aIsDay) a else b)
        },
        // 01/2027, 01-27 (no day: valid to the end of the month)
        Pattern("(?<![0-9A-Z./-])(\\d{1,2})[./-](\\d{4}|\\d{2})(?![0-9/-])(?!\\.[0-9])") { g, _ ->
            Parts(fullYear(g[1].toInt()), g[0].toInt(), null)
        },
        // 11 2027 (month and four-digit year separated only by a space)
        Pattern("$LEAD(0?[1-9]|1[0-2])\\s+(20\\d{2})$TRAIL") { g, _ ->
            Parts(g[1].toInt(), g[0].toInt(), null)
        },
        // 12 JAN 2027, 12-JAN-27, 12JAN2027
        Pattern("$LEAD(\\d{1,2})$SEP?($MONTHS)$SEP?(\\d{4}|\\d{2})$TRAIL") { g, _ ->
            Parts(fullYear(g[2].toInt()), monthNumber(g[1]), g[0].toInt())
        },
        // JAN 12, 2027
        Pattern("$LEAD($MONTHS)$SEP?(\\d{1,2}),?\\s+(\\d{4})$TRAIL") { g, _ ->
            Parts(g[2].toInt(), monthNumber(g[0]), g[1].toInt())
        },
        // JAN 2027, JAN-27 (no day)
        Pattern("$LEAD(?<![0-9]$SEP)($MONTHS)$SEP?(\\d{4}|\\d{2})$TRAIL(?!,?\\s+[0-9]{4})") { g, _ ->
            Parts(fullYear(g[1].toInt()), monthNumber(g[0]), null)
        },
    )

    private fun fullYear(year: Int) = if (year < 100) 2000 + year else year

    private fun monthNumber(name: String) = monthPrefixes.indexOf(name.take(3)) + 1

    private fun makeDate(parts: Parts): LocalDate? {
        if (parts.month !in 1..12) return null
        val daysInMonth = YearMonth.of(parts.year, parts.month).lengthOfMonth()
        val day = parts.day ?: daysInMonth
        if (day !in 1..daysInMonth) return null
        return LocalDate.of(parts.year, parts.month, day)
    }

    private val expiryWords = listOf("EXP", "BEST", "BB", "USE BY", "USE-BY", "BEFORE", "E:")
    private val manufactureWords = listOf("MFG", "MFD", "PKD", "PACK", "PROD", "MANUF", "M:")

    /**
     * Only the keyword closest to the date counts, so "MFG 01/2026 EXP 01/2027"
     * scores each date by its own label.
     */
    private fun score(context: String): Int {
        val expiry = expiryWords.maxOf { context.lastIndexOf(it) }
        val manufacture = manufactureWords.maxOf { context.lastIndexOf(it) }
        return when {
            expiry < 0 && manufacture < 0 -> 0
            expiry > manufacture -> 1
            else -> -1
        }
    }
}
