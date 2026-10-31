package com.elianfabian.parikt

import java.nio.file.Files
import java.nio.file.Path

// JVM implementation for desktop hosts. The matching native libraries must be
// available through java.library.path or the parikt.native.dir system property.
internal actual object PariNative {

    private var temporaryDirectoryConfigured = false

    actual fun loadLibraries() {
        val configuredDirectory = System.getProperty("parikt.native.dir")
        val libraryDirectory = configuredDirectory?.let { Path.of(it) }
        val host = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"

        // The Windows PARI DLL is linked against static GMP; Unix hosts ship it separately.
        val libraries = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("libpari", "paridroid")
        } else {
            listOf("gmp", "pari", "paridroid")
        }

        for (name in libraries) {
            try {
                val file = libraryDirectory?.resolve(System.mapLibraryName(name))
                if (file != null) {
                    require(Files.isRegularFile(file)) {
                        "Native library not found: $file (host: $host)."
                    }
                    System.load(file.toAbsolutePath().toString())
                } else {
                    System.loadLibrary(name)
                }
            } catch (error: UnsatisfiedLinkError) {
                throw UnsatisfiedLinkError(
                    "Unable to load '$name' for $host. Set -Dparikt.native.dir=<directory> " +
                        "or include the matching native library directory in java.library.path. " +
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
        } catch (_: Exception) {
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

    actual external fun eval(cmd: String): String?

    actual external fun getCommands(): Array<String>?

    actual external fun paridroidClose()

    actual external fun pariVersionCode(): Int

    private external fun nativeSetTemporaryDirectory(path: String): Boolean

    private external fun nativeInit(pariSize: Long)
}
