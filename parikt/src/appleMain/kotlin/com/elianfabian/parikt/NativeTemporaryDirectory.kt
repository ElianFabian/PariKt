@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.elianfabian.parikt

import platform.Foundation.NSTemporaryDirectory

// The app/simulator sandbox owns this directory; /tmp is not a mobile sandbox fallback.
internal actual fun nativeTemporaryDirectory(): String = NSTemporaryDirectory()
