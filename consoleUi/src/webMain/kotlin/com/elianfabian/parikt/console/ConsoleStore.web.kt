package com.elianfabian.parikt.console

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.localStorage

@Composable
internal actual fun rememberConsoleStore(): ConsoleStore = remember {
    object : ConsoleStore {
        override fun read() = localStorage.getItem("parikt.console.preferences")
        override fun write(value: String) { localStorage.setItem("parikt.console.preferences", value) }
    }
}
