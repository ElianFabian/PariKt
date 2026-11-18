@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.elianfabian.parikt

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.posix.W_OK
import platform.posix.access
import platform.posix.remove
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Inherited by macOS, iOS, tvOS and watchOS device/simulator test compilations. */
class AppleEngineTest {
    @Test fun sandboxDirectoryAndFileRoundTrip() = runBlocking {
        PariKt.close()
        assertFailsWith<IllegalStateException> { PariKt.eval("2+2") }
        val directory = nativeTemporaryDirectory()
        assertEquals(NSTemporaryDirectory(), directory)
        assertTrue(directory.startsWith('/'))
        assertEquals(0, access(directory, W_OK))
        val filename = directory.trimEnd('/') + "/parikt apple ${Random.nextLong()}.gp"
        val gpFilename = filename.replace("\\", "\\\\").replace("\"", "\\\"")
        PariKt.init()
        try {
            PariKt.eval("write(\"$gpFilename\", 42)")
            assertTrue(PariKt.eval("read(\"$gpFilename\")").trimEnd().endsWith("= 42"))
        } finally {
            PariKt.close()
            assertEquals(0, remove(filename))
        }
    }

    @Test fun nativeBridgeAndReinitialization() = runBlocking {
        PariKt.close()
        repeat(2) {
            PariKt.init()
            try {
                assertTrue(PariKt.getVersionCode() > 0)
                assertTrue("factor" in PariKt.getSupportedCommands())
                assertTrue(PariKt.eval("factor(123456)==[2,6;3,1;643,1]").trimEnd().endsWith("= 1"))
                assertTrue(PariKt.eval("1/0").isNotBlank())
                assertTrue(PariKt.eval("6*7").trimEnd().endsWith("= 42"))
            } finally { PariKt.close() }
        }
    }
}
