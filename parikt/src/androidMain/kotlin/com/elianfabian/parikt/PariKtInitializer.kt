package com.elianfabian.parikt

import android.content.Context
import androidx.startup.Initializer

/** Initializes the PARI/GP engine automatically during Android app startup. */
public class PariKtInitializer : Initializer<Unit> {

    public override fun create(context: Context): Unit {
        val appContext = context.applicationContext
        val cacheDirectory = appContext.cacheDir
        check(cacheDirectory.exists() || cacheDirectory.mkdirs()) {
            "Unable to create the app cache directory at '${cacheDirectory.absolutePath}'."
        }
        PariKt.initWithTemporaryDirectory(4_000_000L, cacheDirectory.absolutePath)
    }

    public override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
