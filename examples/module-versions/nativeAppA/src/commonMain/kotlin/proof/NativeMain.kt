package proof

import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking { verifySharedNativeEngine(args.single()) }
