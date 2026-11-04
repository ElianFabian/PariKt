package com.elianfabian.parikt

import android.content.Context
import androidx.startup.Initializer

/** Records the app's temporary directory; the application chooses when to call [PariKt.init]. */
public class PariKtInitializer : Initializer<Unit> {

	public override fun create(context: Context): Unit {
		val appContext = context.applicationContext
		val cacheDirectory = appContext.cacheDir
		check(cacheDirectory.exists() || cacheDirectory.mkdirs()) {
			"Unable to create the app cache directory at '${cacheDirectory.absolutePath}'."
		}
		AndroidEnvironment.temporaryDirectory = cacheDirectory.absolutePath
	}

	public override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
