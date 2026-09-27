package dev.stratus.app.e2e

import org.junit.Test

class SignInTest : E2E() {

    @Test
    fun signsInOverPlainHttpOnceTheRiskIsAccepted() {
        launch()
        signIn()
        see("Servers")
        // No photo permission yet, and the strip says that before anything else.
        see("cannot read your photographs")
    }

    @Test
    fun aWrongPasswordIsSaidInThoseWordsAndKeepsTheForm() {
        launch()
        signIn(password = "not-the-password")
        see("did not accept that username and password")
        see("Sign in")
    }

    @Test
    fun refusingPlainHttpSendsNothingAndSaysSo() {
        launch()
        type("Server address", link.address)
        type("Username", Stratus.user)
        type("Password", Stratus.password)
        tap("Sign in")
        tap("Cancel")
        see("Nothing was sent to")
    }

    @Test
    fun aServerThatIsOffIsSaidAndSigningInWorksOnceItIsBack() {
        link.down()
        launch()
        type("Server address", link.address)
        type("Username", Stratus.user)
        type("Password", Stratus.password)
        tap("Sign in")
        // The probe that finds the server is anonymous, so it fails before
        // anybody is asked to accept a risk for a host that is not there.
        see("Could not reach", timeoutMs = 40_000)

        link.up()
        tap("Sign in")
        tap("Send anyway")
        see("Servers")
    }

    @Test
    fun theSessionSurvivesTheAppBeingClosed() {
        signedIn()
        Phone.device.pressBack()
        Phone.toHomeAndBack()
        see("Servers")
    }
}
