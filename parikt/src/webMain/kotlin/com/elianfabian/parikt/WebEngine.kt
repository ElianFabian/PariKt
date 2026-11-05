package com.elianfabian.parikt

@JsModule("@parikt/engine")
private external fun createPari(): WebEngineLoading

internal fun loadPariModule(): WebEngineLoading = createPari()

// Opaque Promise handle keeps version-specific Wasm generic bounds out of shared sources.
internal expect interface WebEngineLoading

internal expect interface WebEngine {
	fun init(stackBytes: Int)
	fun eval(command: String): String
	fun getVersionCode(): Int
	fun close()
}

// JS and Wasm expose different array representations on earlier Kotlin 2.x versions.
internal expect fun engineCommands(engine: WebEngine): Array<String>
