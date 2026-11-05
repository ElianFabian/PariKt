package com.elianfabian.parikt.console

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberConsoleStore(): ConsoleStore {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val preferences = context.getSharedPreferences("parikt_console", Context.MODE_PRIVATE)
        object : ConsoleStore {
            override fun read() = preferences.getString("preferences", null)
            override fun write(value: String) {
                check(preferences.edit().putString("preferences", value).commit()) { "Android preferences write failed." }
            }
        }
    }
}
