package dev.stratus.app.e2e

import dev.stratus.ui.Glyph
import org.junit.Test

/**
 * Which build this is, which is the question somebody has before reporting
 * that anything else is wrong.
 *
 * The value cannot be asserted from here -- the test does not know which
 * commit it was built from -- so what is asserted is the two things that can
 * be: that the row acts, and that it is not showing the placeholder a build
 * made outside git would. That negative is the one that matters, because the
 * way this breaks is the Makefile quietly stopping to pass the values and the
 * screen still looking fine.
 */
class AboutTest : E2E() {

    @Test
    fun theBuildIsOnTheMenuAndItsCommitCanBeCopied() {
        signedIn()
        openSettings()

        see(Glyph.BUILD)
        gone("dev · unknown")

        tap("Build")
        see("Copied")
    }
}
