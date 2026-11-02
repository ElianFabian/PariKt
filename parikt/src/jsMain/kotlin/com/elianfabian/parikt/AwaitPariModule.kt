@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.elianfabian.parikt

import kotlinx.coroutines.await
import kotlin.js.Promise

internal actual suspend fun awaitPariModule(loading: Promise<WebEngine>): WebEngine = loading.await()
