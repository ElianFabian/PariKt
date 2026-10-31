package com.elianfabian.parikt

/**
 * Common KMP entry point for the PariKt PARI/GP engine.
 * Delegating to platform-specific [PariNative] implementation.
 */
public object PariKt {

	private var _initialized = false

	/**
	 * Initialize the PARI/GP engine with the given stack size.
	 * @param pariSize Stack size in bytes (default: 4,000,000)
	 */
	public fun init(pariSize: Long = 4_000_000L) {
		initialize(pariSize, null)
	}

	/** Internal Android entry point for supplying the host app's cache directory. */
	internal fun initWithTemporaryDirectory(pariSize: Long, path: String) {
		initialize(pariSize, path)
	}

	private fun initialize(pariSize: Long, temporaryDirectory: String?) {
		if (_initialized) return
		PariNative.loadLibraries()
		if (temporaryDirectory != null) {
			check(PariNative.setTemporaryDirectory(temporaryDirectory)) {
				"Unable to set PARI's temporary directory to '$temporaryDirectory'."
			}
		}
		PariNative.init(pariSize)
		_initialized = true
	}

	/**
	 * Evaluate a GP command and return the result as a string.
	 * @param cmd GP command to evaluate
	 * @return Result of the evaluation
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
		return PariNative.getCommands().orEmpty().toList()
	}

	/**
	 * Close the PARI/GP engine and release resources.
	 */
	public fun close() {
		PariNative.paridroidClose()
		_initialized = false
	}

	/**
	 * Get the PARI version code.
	 * @return Version code as integer
	 */
	public fun getVersionCode(): Int = PariNative.pariVersionCode()
}
