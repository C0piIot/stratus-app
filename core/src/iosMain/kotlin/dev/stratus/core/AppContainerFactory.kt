package dev.stratus.core

import dev.stratus.core.files.IosFileHandoff
import dev.stratus.core.store.KeychainSecureStore
import io.ktor.client.engine.darwin.Darwin

/** See the Android twin. */
fun appContainer(): AppContainer = AppContainer(
    engine = { Darwin.create() },
    secure = KeychainSecureStore(),
    handoff = IosFileHandoff(),
)
