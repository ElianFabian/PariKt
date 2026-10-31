package com.elianfabian.parikt

import android.util.Log

// Android-specific implementation of PariNative using JNI
internal actual object PariNative {

	const val MSG_TAG: String = "PariKt::PariNative"

	init {
		loadLibrary("gmp")
		loadLibrary("pari")
		loadLibrary("paridroid")
	}

	actual fun loadLibraries() {
		// Libraries are loaded in init block
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
		check(temporaryDirectoryConfigured) {
			"Android requires an app cache directory. Initialize PariKt through AndroidX Startup before use."
		}
		nativeInit(pariSize)
	}

	private external fun nativeSetTemporaryDirectory(path: String): Boolean

	private external fun nativeInit(pariSize: Long)

	actual external fun eval(cmd: String): String?

	actual external fun getCommands(): Array<String>?

	actual external fun paridroidClose()

	actual external fun pariVersionCode(): Int
}
