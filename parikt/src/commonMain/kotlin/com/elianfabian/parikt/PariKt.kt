package com.elianfabian.parikt

/**
 * Common KMP entry point for the PariKt PARI/GP engine.
 * Delegating to platform-specific [PariNative] implementation.
 */
public object PariKt {

	private var _initialized = false

	/**
	 * Load the platform engine and initialize PARI with the given stack size.
	 * Web targets suspend while loading their engine. Every target requires this explicit call.
	 * Calls after initialization have no effect.
	 * @param pariSize Positive stack size in bytes (default: 4,000,000).
	 * Web targets require a positive value that fits a signed 32-bit integer. Successful
	 * initialization still requires enough stack space and available memory.
	 */
	public suspend fun init(pariSize: Long = 4_000_000L) {
		if (_initialized) return
		require(pariSize > 0) { "PARI stack size must be positive." }
		PariNative.prepareLibraries()
		// Another caller may finish initialization while web engine loading suspends.
		if (_initialized) return
		PariNative.loadLibraries()
		PariNative.init(pariSize)
		_initialized = true
	}

	/**
	 * Evaluate a GP command and return the result as a string.
	 * @param cmd GP command to evaluate
	 * @return Result of the evaluation
	 * GP quit() returns a recoverable error; use [close] to release the engine.
	 * @throws IllegalStateException if engine not initialized
	 */
	public fun eval(cmd: String): String {
		check(_initialized) { "Engine not initialized. Call PariKt.init() first." }
		return PariNative.eval(cmd) ?: error("Evaluation returned null for command: $cmd")
	}

	/**
	 * Get the list of supported GP commands.
	 * @return List of supported commands
	 * @throws IllegalStateException if engine not initialized
	 */
	public fun getSupportedCommands(): List<String> {
		check(_initialized) { "Engine not initialized. Call PariKt.init() first." }
		return PariNative.getSupportedCommands().orEmpty().toList()
	}

	/**
	 * Close the PARI/GP engine and release resources.
	 * Calling this before initialization or after an earlier close has no effect.
	 */
	public fun close() {
		if (!_initialized) return
		PariNative.close()
		_initialized = false
	}

	/**
	 * Get the PARI version code.
	 * @return Version code as integer
	 */
	public fun getVersionCode(): Int = PariNative.getVersionCode()
}
