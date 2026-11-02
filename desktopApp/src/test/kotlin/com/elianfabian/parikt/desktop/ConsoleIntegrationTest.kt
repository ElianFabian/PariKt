package com.elianfabian.parikt.desktop

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.console.ConsoleApp
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ConsoleIntegrationTest {
	@get:Rule val compose = createComposeRule()

	@After fun closeEngine() { compose.runOnUiThread { PariKt.close() } }

	@Test fun realEngineStartupExamplesAndCommandEntry() {
		compose.setContent { ConsoleApp() }
		compose.onNodeWithTag("engine-status").assertTextContains("Ready", substring = true)
		compose.onNodeWithText("Factor a number").clickOnUiThread()
		compose.onNodeWithTag("run-command").assertIsEnabled().clickOnUiThread()
		compose.onNodeWithText("643", substring = true).assertExists()
		compose.onNodeWithTag("clear-output").clickOnUiThread()
		compose.onNodeWithText("Command results will appear here.").assertExists()
		compose.onNodeWithTag("command-input").performTextInput("Pi")
		compose.onNodeWithTag("run-command").clickOnUiThread()
		compose.onNodeWithText("3.14159", substring = true).assertExists()
	}

	private fun SemanticsNodeInteraction.clickOnUiThread() {
		// The 1.8.2 headless mouse injector invokes callbacks on the test worker.
		// Invoke the button action on AWT, matching real desktop input and PARI init.
		val action = fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
		compose.runOnUiThread { action() }
		compose.waitForIdle()
	}
}
