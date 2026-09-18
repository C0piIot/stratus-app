package dev.stratus.core.backup

/**
 * A moment in time, taken apart in UTC.
 *
 * **UTC and not the device's timezone, and that is the whole point.** Both
 * platforms report a capture time as milliseconds since the epoch, and turning
 * that into a local wall clock needs a zone -- which means the same photograph
 * would derive a different path after its owner flew somewhere, and a path that
 * moves is the entire camera roll uploading itself again. The cost is that a
 * picture taken at half past midnight in Madrid files under the previous day.
 */
data class UtcParts(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/**
 * Splits epoch milliseconds into UTC parts, by arithmetic alone.
 *
 * Done here rather than with each platform's calendar so there is one
 * implementation and one set of tests, instead of two -- of which the iOS one
 * could only be read and never run.
 */
fun utcPartsOf(epochMillis: Long): UtcParts {
    val seconds = floorDiv(epochMillis, 1000)
    val days = floorDiv(seconds, 86_400)
    val secondOfDay = seconds - days * 86_400

    // Howard Hinnant's civil-from-days: the era arithmetic that makes leap years
    // and centuries fall out without a table.
    var z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val dayOfEra = z - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    var year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
    val month = monthPrime + if (monthPrime < 10) 3 else -9
    if (month <= 2) year++

    return UtcParts(
        year = year.toInt(),
        month = month.toInt(),
        day = day.toInt(),
        hour = (secondOfDay / 3_600).toInt(),
        minute = (secondOfDay % 3_600 / 60).toInt(),
        second = (secondOfDay % 60).toInt(),
    )
}

private fun floorDiv(a: Long, b: Long): Long {
    val quotient = a / b
    return if (a % b != 0L && (a xor b) < 0) quotient - 1 else quotient
}
