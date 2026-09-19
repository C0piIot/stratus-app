package dev.stratus.core.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * What the Cast SDK reads at startup, found by name from the manifest.
 *
 * **Google's default receiver**, which is the whole reason this feature costs
 * nothing: a receiver of our own would mean a five-dollar registration, a Google
 * account and an HTTPS page to host it, and the only thing it would buy is the
 * ability to send an `Authorization` header. A signed link makes that
 * unnecessary -- the television fetches an ordinary URL that needs no
 * credentials at all.
 */
class StratusCastOptions : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            // The SDK would otherwise resume whatever was playing when the app
            // comes back, which for a photograph somebody cast yesterday is not
            // a welcome surprise.
            .setResumeSavedSession(false)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
