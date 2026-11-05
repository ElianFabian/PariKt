package com.elianfabian.parikt.console

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileConsoleStoreTest {
    @Test fun settingsAndScriptsSurviveNewStoreInstances() {
        val directory = Files.createTempDirectory("parikt-console-test-")
        val path = directory.resolve("preferences")
        try {
            assertNull(FileConsoleStore(path).read())
            val preferences = ConsolePreferences(EngineSettings(8_000_000), listOf(SavedScript("π🙂", "f(x)=x^2;\nf(12)")))
            FileConsoleStore(path).write(ConsolePreferencesCodec.encode(preferences))
            assertEquals(preferences, ConsolePreferencesCodec.decode(FileConsoleStore(path).read()!!))
            FileConsoleStore(path).write(ConsolePreferencesCodec.encode(ConsolePreferences()))
            assertEquals(ConsolePreferences(), ConsolePreferencesCodec.decode(FileConsoleStore(path).read()!!))
        } finally {
            Files.deleteIfExists(path)
            Files.delete(directory)
        }
    }
}
