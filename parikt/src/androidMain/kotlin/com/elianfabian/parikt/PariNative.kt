package com.elianfabian.parikt

import android.util.Log

// Android-specific implementation of PariNative using JNI
internal actual object PariNative {

	actual suspend fun prepareLibraries() {}

	const val MSG_TAG: String = "PariKt::PariNative"

	private var librariesLoaded = false

	actual fun loadLibraries() {
		if (librariesLoaded) return
		loadLibrary("gmp")
		loadLibrary("pari")
		loadLibrary("paridroid")
		librariesLoaded = true
	}

	private var temporaryDirectoryConfigured = false

	actual fun setTemporaryDirectory(path: String): Boolean {
		if (path.isBlank()) return false
		temporaryDirectoryConfigured = nativeSetTemporaryDirectory(path)
		return temporaryDirectoryConfigured
	}

	private fun loadLibrary(name: String) {
		try {
			Log.i(MSG_TAG, "Loading lib$name.so")
			System.loadLibrary(name)
		}
		catch (error: UnsatisfiedLinkError) {
			Log.e(MSG_TAG, "Unable to load lib$name.so: ${error.message}", error)
			throw UnsatisfiedLinkError(
				"Unable to load lib$name.so. Confirm the library is packaged for the device ABI and " +
					"that its native dependencies are available."
			).also { it.initCause(error) }
		}
	}

	actual fun init(pariSize: Long) {
		val directory = checkNotNull(AndroidEnvironment.temporaryDirectory) {
			"Android temporary directory is unavailable. Enable PariKt's AndroidX Startup initializer."
		}
		if (!temporaryDirectoryConfigured) {
			check(setTemporaryDirectory(directory)) { "Unable to configure PARI's temporary directory at '$directory'." }
		}
		nativeInit(pariSize)
	}

	private external fun nativeSetTemporaryDirectory(path: String): Boolean

	private external fun nativeInit(pariSize: Long)

	actual fun eval(cmd: String): String? = nativeEval(cmd)

	actual fun getSupportedCommands(): Array<String>? = nativeGetSupportedCommands()

	actual fun close() = nativeClose()

	actual fun getVersionCode(): Int = nativeGetVersionCode()

	private external fun nativeEval(cmd: String): String?

	private external fun nativeGetSupportedCommands(): Array<String>?

	private external fun nativeClose()

	private external fun nativeGetVersionCode(): Int
}
