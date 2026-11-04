package com.elianfabian.parikt

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebEngineTest {
    @Test fun boundaryContracts() = runTest { EngineBoundaryChecks.all() }
	@Test
	fun engineLifecycleAndEvaluation() = runTest {
		assertFailsWith<IllegalStateException> { PariKt.eval("2+2") }
		val cancelledLoader = launch(start = CoroutineStart.UNDISPATCHED) { PariKt.init() }
		cancelledLoader.cancelAndJoin()
		PariKt.init()
		try {
			assertTrue(PariKt.getVersionCode() > 0)
			assertTrue(PariKt.getVersionName().matches(Regex("\\d+\\.\\d+\\.\\d+")))
			fun result(command: String): String = PariKt.eval(command).substringAfter("= ").trim()
			assertEquals("4", result("2+2"))
			assertEquals("1", result("factor(123456)==[2,6;3,1;643,1]"))
			PariKt.eval("parikt_value=41;")
			assertEquals("42", result("parikt_value+1"))
			assertEquals("", PariKt.eval(""))
			assertTrue(PariKt.eval("1/0").contains(Regex("division|inverse|zero", RegexOption.IGNORE_CASE)))
			assertEquals("4", result("2+2"))
			assertContains(PariKt.getSupportedCommands(), "factor")
		} finally { PariKt.close() }
		PariKt.close()
		assertFailsWith<IllegalStateException> { PariKt.eval("2+2") }
	}
}
