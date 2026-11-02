package com.elianfabian.parikt

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeEngineTest {
	@Test fun engineLifecycleAndEvaluation() = runBlocking {
		assertFailsWith<IllegalStateException> { PariKt.eval("2+2") }
		assertFailsWith<IllegalArgumentException> { PariKt.init(0) }
		PariKt.init()
		try {
			PariKt.init()
			assertTrue(PariKt.getVersionName().matches(Regex("\\d+\\.\\d+\\.\\d+")))
			fun result(command: String) = PariKt.eval(command).substringAfter("= ").trim()
			assertEquals("4", result("2+2"))
			assertEquals("1", result("factor(123456)==[2,6;3,1;643,1]"))
			PariKt.eval("parikt_value=41;")
			assertEquals("42", result("parikt_value+1"))
			assertEquals("", PariKt.eval(""))
			assertFailsWith<IllegalArgumentException> { PariKt.eval("2+2\u0000+1") }
			assertTrue(PariKt.eval("1/0").contains(Regex("division|inverse|zero", RegexOption.IGNORE_CASE)))
			assertEquals("4", result("2+2"))
			assertContains(PariKt.getSupportedCommands(), "factor")
			withContext(Dispatchers.Default) {
				assertFailsWith<IllegalStateException> { PariKt.eval("2+2") }
				assertFailsWith<IllegalStateException> { PariKt.getSupportedCommands() }
				assertFailsWith<IllegalStateException> { PariKt.close() }
			}
			assertEquals("42", result("parikt_value+1"))
		} finally { PariKt.close() }
		PariKt.close()
		assertFailsWith<IllegalStateException> { PariKt.eval("2+2") }
		PariKt.init()
		try { assertTrue(PariKt.eval("2+2").trim().endsWith("4")) }
		finally { PariKt.close() }
	}
}
