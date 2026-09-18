package dev.stratus.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals

class UtcTimeTest {

    @Test
    fun splitsTheEpochItself() {
        assertEquals(UtcParts(1970, 1, 1, 0, 0, 0), utcPartsOf(0))
    }

    @Test
    fun splitsAKnownMoment() {
        // 2026-09-18T09:30:00Z
        assertEquals(UtcParts(2026, 9, 18, 9, 30, 0), utcPartsOf(1_789_723_800_000))
    }

    @Test
    fun getsLeapDaysRight() {
        // 2024 is a leap year, 1900 was not, 2000 was.
        assertEquals(UtcParts(2024, 2, 29, 12, 0, 0), utcPartsOf(1_709_208_000_000))
        assertEquals(UtcParts(2000, 2, 29, 0, 0, 0), utcPartsOf(951_782_400_000))
        assertEquals(UtcParts(1900, 3, 1, 0, 0, 0), utcPartsOf(-2_203_891_200_000))
    }

    @Test
    fun handlesTheEdgesOfADay() {
        assertEquals(UtcParts(2026, 1, 1, 0, 0, 0), utcPartsOf(1_767_225_600_000))
        assertEquals(UtcParts(2025, 12, 31, 23, 59, 59), utcPartsOf(1_767_225_599_000))
    }

    @Test
    fun copesWithMomentsBeforeTheEpoch() {
        // A scanned photograph can carry a date from the nineteen sixties.
        assertEquals(UtcParts(1969, 12, 31, 23, 59, 59), utcPartsOf(-1_000))
        assertEquals(UtcParts(1960, 6, 15, 12, 0, 0), utcPartsOf(-301_233_600_000))
    }
}
