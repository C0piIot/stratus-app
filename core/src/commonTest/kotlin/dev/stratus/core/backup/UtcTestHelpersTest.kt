package dev.stratus.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals

class UtcTestHelpersTest {

    @Test
    fun theHelperAgreesWithTheThingItHelpsTest() {
        // A helper that lies makes every test using it lie too.
        for (parts in listOf(
            UtcParts(1970, 1, 1, 0, 0, 0),
            UtcParts(2026, 9, 18, 9, 30, 0),
            UtcParts(2024, 2, 29, 23, 59, 59),
            UtcParts(1960, 6, 15, 12, 0, 0),
        )) {
            val millis = utcMillis(parts.year, parts.month, parts.day, parts.hour, parts.minute, parts.second)
            assertEquals(parts, utcPartsOf(millis))
        }
    }
}
