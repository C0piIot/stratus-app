package dev.stratus.core.backup

/**
 * The console, and no Sentry half at all.
 *
 * Not an oversight and not easily fixed: Sentry lives in `:ui` precisely so
 * that `:core`'s iOS test executable does not have to link Sentry Cocoa, and
 * `:core` cannot reach upwards to call it. So the Android half of this log can
 * be read from anywhere and this one needs Xcode attached.
 *
 * `println` rather than `NSLog`, which is the one that would reach Console.app
 * from a device with nothing attached to it: it is a variadic C function, and
 * whether cinterop makes one callable from Kotlin is not something this
 * project can find out -- no Apple target compiles on the development box
 * (stratus-app#117).
 */
internal actual fun platformLogSink(): LogSink = ConsoleLogSink
