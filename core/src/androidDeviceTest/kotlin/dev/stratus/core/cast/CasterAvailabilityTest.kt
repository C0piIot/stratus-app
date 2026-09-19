package dev.stratus.core.cast

import androidx.test.platform.app.InstrumentationRegistry
import dev.stratus.core.casterFor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A phone without Google Play Services keeps the whole app and loses the button.
 *
 * The emulator CI runs is an `aosp-atd` image, which carries no Play Services --
 * so this is that phone, for free, on every run. If the image ever changes this
 * fails, and the answer is to read it again rather than to delete it: the
 * requirement is that a de-Googled phone is not a broken one.
 */
class CasterAvailabilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun withoutPlayServicesThereIsNothingToCastWithAndNothingBreaks() {
        val caster = casterFor(context)

        assertFalse(caster.available, "found a Cast SDK on an image that has no Play Services")
        assertTrue(caster.devices.value.isEmpty())

        // Not a crash, not an exception: the calls a screen might make anyway
        // do nothing at all.
        caster.startDiscovery()
        caster.stopDiscovery()
    }
}
