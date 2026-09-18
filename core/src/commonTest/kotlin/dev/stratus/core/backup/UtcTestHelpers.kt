package dev.stratus.core.backup

/**
 * The inverse of [utcPartsOf], so a test can name a date instead of a number.
 *
 * Lives here rather than in the app because nothing there needs it: both
 * platforms hand over epoch milliseconds already.
 */
fun utcMillis(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long {
    // Howard Hinnant's days-from-civil, the mirror of the split in UtcTime.kt.
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthPrime = if (month > 2) month - 3 else month + 9
    val dayOfYear = (153 * monthPrime + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    val days = era * 146_097 + dayOfEra - 719_468
    return ((days * 86_400) + hour * 3_600 + minute * 60 + second) * 1_000
}
