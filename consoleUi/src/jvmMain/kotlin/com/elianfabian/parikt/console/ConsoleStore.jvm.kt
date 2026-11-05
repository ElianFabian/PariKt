package com.elianfabian.parikt.console

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Composable
internal actual fun rememberConsoleStore(): ConsoleStore = remember {
    FileConsoleStore(Path.of(System.getProperty("user.home"), ".parikt", "console.preferences"))
}

internal class FileConsoleStore(private val path: Path) : ConsoleStore {
    override fun read(): String? = if (Files.exists(path)) Files.readString(path) else null
    override fun write(value: String) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".console-", ".tmp")
        try {
            Files.writeString(temporary, value)
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}
