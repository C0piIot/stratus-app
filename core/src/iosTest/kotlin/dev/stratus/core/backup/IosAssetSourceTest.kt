package dev.stratus.core.backup

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a test binary can honestly say about the camera roll, which is not much
 * (stratus-app#20).
 *
 * A Kotlin/Native test has no app bundle, so it has no usage description and
 * no authorisation -- the same wall `KeychainSecureStore` meets. It can
 * therefore prove exactly one thing, and it is worth proving: **the unauthorised
 * path degrades rather than throws.** That is the state the rest of the app
 * already handles, and the one a person who refused the permission is in.
 *
 * `request()` is deliberately not called here. Asking without a usage
 * description does not fail -- it kills the process -- so a test that reached
 * for it would take the whole suite with it.
 *
 * Everything else about this class goes to stratus-app#117, for a person with
 * a device.
 */
class IosAssetSourceTest {

    private val source = IosAssetSource()

    @Test
    fun withoutAuthorisationItReportsNoneRatherThanFailing() = runTest {
        assertEquals(MediaAccess.None, source.access())
    }

    @Test
    fun andAnswersEmptyRatherThanThrowing() = runTest {
        // A backup that threw here would be a crash on the first screen of a
        // phone where somebody said no, which is a state and not an error.
        assertTrue(source.sources().isEmpty())
        assertTrue(source.assets(from = emptySet(), addedAfterEpochMs = 0).isEmpty())
    }
}
