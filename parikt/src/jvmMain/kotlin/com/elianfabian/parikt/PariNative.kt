package com.elianfabian.parikt

import java.nio.file.Files
import java.nio.file.Path
import java.io.File

// JVM implementation for desktop hosts. The matching native libraries must be
// available through java.library.path.
internal actual object PariNative {

	actual suspend fun prepareLibraries() {}

	private var temporaryDirectoryConfigured = false

	actual fun loadLibraries() {
		val nativeLibraryPath = System.getProperty("java.library.path")
		val host = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"

		// Windows PARI DLL names vary by PARI release/configuration. Load the exact library
		// basename shipped beside the JNI bridge; Unix hosts ship GMP separately.
		val libraries = if (System.getProperty("os.name").startsWith("Windows")) {
			val pariLibrary = nativeLibraryPath
				.orEmpty()
				.split(File.pathSeparator)
				.asSequence()
				.map(::File)
				.flatMap { directory -> directory.listFiles().orEmpty().asSequence() }
				.firstOrNull { file ->
					file.isFile && file.extension.equals("dll", ignoreCase = true) &&
						(file.name.startsWith("libpari", ignoreCase = true) || file.name.equals("pari.dll", ignoreCase = true))
				}
				?.name
				?.removeSuffix(".dll")
				?: "libpari-gmp"
			listOf(pariLibrary, "paridroid")
		}
		else {
			listOf("gmp", "pari", "paridroid")
		}

		for (name in libraries) {
			try {
				System.loadLibrary(name)
			}
			catch (error: UnsatisfiedLinkError) {
				throw UnsatisfiedLinkError(
					"Unable to load '$name' for $host. Add the matching native library directory " +
						"to java.library.path (currently '$nativeLibraryPath'). " +
						"${error.message}"
				).also { it.initCause(error) }
			}
		}
	}

	actual fun setTemporaryDirectory(path: String): Boolean {
		if (path.isBlank()) return false
		return try {
			val directory = Path.of(path).toAbsolutePath().normalize()
			Files.createDirectories(directory)
			temporaryDirectoryConfigured = nativeSetTemporaryDirectory(directory.toString())
			temporaryDirectoryConfigured
		}
		catch (_: Exception) {
			false
		}
	}

	actual fun init(pariSize: Long) {
		if (!temporaryDirectoryConfigured) {
			val defaultDirectory = Path.of(System.getProperty("java.io.tmpdir"), "parikt")
			check(setTemporaryDirectory(defaultDirectory.toString())) {
				"Unable to prepare PARI's temporary directory at '$defaultDirectory'."
			}
		}
		check(temporaryDirectoryConfigured) {
			"JVM PARI temporary directory was not configured. Call PariKt.init() after native libraries are available."
		}
		nativeInit(pariSize)
	}

	actual fun eval(cmd: String): String? = nativeEval(cmd)

	actual fun getSupportedCommands(): Array<String>? = nativeGetSupportedCommands()

	actual fun close() = nativeClose()

	actual fun getVersionCode(): Int = nativeGetVersionCode()

	private external fun nativeSetTemporaryDirectory(path: String): Boolean

	private external fun nativeInit(pariSize: Long)

	private external fun nativeEval(cmd: String): String?

	private external fun nativeGetSupportedCommands(): Array<String>?

	private external fun nativeClose()

	private external fun nativeGetVersionCode(): Int
}
