package dev.stratus.core.backup

/** The JVM target ships nowhere; this exists so the shared code links there. */
internal actual fun platformLogSink(): LogSink = ConsoleLogSink
