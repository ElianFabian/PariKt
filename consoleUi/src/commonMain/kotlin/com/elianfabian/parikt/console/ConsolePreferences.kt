package com.elianfabian.parikt.console

import androidx.compose.runtime.Composable

internal data class EngineSettings(val stackSize: Long = 4_000_000, val maximumStackSize: Long = 0) {
    init {
        require(stackSize in 1..Int.MAX_VALUE.toLong()) { "parisize must be between 1 and ${Int.MAX_VALUE} bytes." }
        require(maximumStackSize == 0L || maximumStackSize in stackSize..Int.MAX_VALUE.toLong()) {
            "parisizemax must be 0 or at least parisize, up to ${Int.MAX_VALUE} bytes."
        }
    }
    companion object {
        fun parse(stack: String, maximum: String) = EngineSettings(
            stack.trim().toLongOrNull() ?: throw IllegalArgumentException("Enter a whole number for parisize."),
            maximum.trim().toLongOrNull() ?: throw IllegalArgumentException("Enter a whole number for parisizemax."),
        )
    }
}

internal data class SavedScript(val name: String, val code: String)
internal data class ConsolePreferences(val settings: EngineSettings = EngineSettings(), val scripts: List<SavedScript> = emptyList())
internal interface ConsoleStore {
    fun read(): String?
    fun write(value: String)
}
internal class MemoryConsoleStore : ConsoleStore {
    private var value: String? = null
    override fun read() = value
    override fun write(value: String) { this.value = value }
}
@Composable
internal expect fun rememberConsoleStore(): ConsoleStore

/** Length-prefixed fields preserve multiline GP code without a platform JSON dependency. */
internal object ConsolePreferencesCodec {
    fun encode(preferences: ConsolePreferences): String = buildString {
        append("PariKt-console-1\n")
        val fields = listOf(preferences.settings.stackSize.toString(), preferences.settings.maximumStackSize.toString()) +
            preferences.scripts.flatMap { listOf(it.name, it.code) }
        fields.forEach { append(it.length).append(':').append(it) }
    }
    fun decode(value: String): ConsolePreferences {
        val header = "PariKt-console-1\n"
        require(value.startsWith(header)) { "Unknown saved console format." }
        var position = header.length
        fun field(): String {
            val separator = value.indexOf(':', position)
            require(separator >= position) { "Incomplete saved console field." }
            val length = value.substring(position, separator).toIntOrNull()
            require(length != null && length >= 0 && length <= value.length - separator - 1) { "Invalid saved console field." }
            position = separator + 1
            return value.substring(position, position + length).also { position += length }
        }
        val settings = EngineSettings.parse(field(), field())
        val scripts = mutableListOf<SavedScript>()
        while (position < value.length) {
            val script = SavedScript(field(), field())
            require(script.name.isNotBlank() && script.code.isNotBlank()) { "Empty saved script." }
            require(scripts.none { it.name == script.name }) { "Duplicate saved script name." }
            scripts.add(script)
        }
        return ConsolePreferences(settings, scripts)
    }
}
