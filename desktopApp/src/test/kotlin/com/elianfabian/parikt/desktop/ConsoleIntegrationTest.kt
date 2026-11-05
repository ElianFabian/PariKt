package com.elianfabian.parikt.desktop

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import com.elianfabian.parikt.console.ConsoleSession
import com.elianfabian.parikt.console.ConsoleApp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class ConsoleIntegrationTest {
	@get:Rule val compose = createComposeRule()

	@After fun closeEngine() { compose.runOnUiThread { ConsoleSession.close() } }

	@Test fun realEngineStartupExamplesAndCommandEntry() {
		compose.setContent { Box(Modifier.size(400.dp, 720.dp)) { ConsoleApp(persistPreferences = false) } }
		compose.onNodeWithTag("engine-status").assertTextContains("Ready", substring = true)
		compose.runOnUiThread {
			val expected = System.getProperty("parikt.expected.version")
			if (expected !in setOf("master", "latest")) assertEquals(expected, ConsoleSession.version())
		}
		openMenuItem("Factor a number")
		compose.onNodeWithTag("run-command").assertIsEnabled().clickOnUiThread()
		compose.onNodeWithText("643", substring = true).assertExists()
		compose.onNodeWithTag("previous-command").clickOnUiThread()
		compose.onNodeWithTag("command-input").assertTextContains("factor(123456)")
		openMenuItem("Clear output")
		compose.onNodeWithText("Command results will appear here.").assertExists()
		compose.onNodeWithTag("command-input").performTextReplacement("fac")
		compose.onNodeWithTag("completion-factor").clickOnUiThread()
		compose.onNodeWithTag("command-input").assertTextContains("factor")
		compose.onNodeWithTag("command-input").performTextReplacement("Pi")
		compose.onNodeWithTag("run-command").clickOnUiThread()
		compose.onNodeWithText("3.14159", substring = true).assertExists()
		capture("console")

		openMenuItem("Custom scripts")
		compose.onNodeWithTag("new-script").clickOnUiThread()
		compose.onNodeWithTag("script-name").performTextInput("Square")
		compose.onNodeWithTag("script-code").performTextInput("sq(x)=x^2")
		compose.onNodeWithTag("save-script").clickOnUiThread()
		compose.onNodeWithText("Square").assertExists()
		capture("scripts")
		compose.onNodeWithText("Run").clickOnUiThread()
		compose.onNodeWithTag("command-input").performTextReplacement("sq(12)")
		compose.onNodeWithTag("run-command").clickOnUiThread()
		capture("script-result")
		compose.onNodeWithText("144", substring = true).assertExists()

		openMenuItem("Settings")
		capture("settings")
		compose.onNodeWithTag("stack-size").performTextReplacement("8000000")
		compose.onNodeWithTag("maximum-stack-size").performTextReplacement("16000000")
		compose.onNodeWithTag("apply-settings").clickOnUiThread()
		compose.onNodeWithTag("engine-status").assertTextContains("Ready", substring = true)
		compose.runOnUiThread {
			val maximum = ConsoleSession.evaluate("default(parisizemax)").substringAfterLast('=').trim().toLong()
			org.junit.Assert.assertTrue(maximum >= 16_000_000)
		}
	}

	private fun openMenuItem(text: String) {
		compose.onNodeWithTag("console-menu").clickOnUiThread()
		compose.onNodeWithText(text).clickOnUiThread()
	}

	private fun capture(name: String) {
		val image = compose.onRoot().captureToImage()
		val pixels = image.toPixelMap()
		val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until image.height) for (x in 0 until image.width) bitmap.setRGB(x, y, pixels[x, y].toArgb())
		val directory = File("build/reports/sample-ui").also { it.mkdirs() }
		ImageIO.write(bitmap, "png", File(directory, "$name.png"))
	}

	private fun SemanticsNodeInteraction.clickOnUiThread() {
		// The 1.8.2 headless mouse injector invokes callbacks on the test worker.
		// Invoke the button action on AWT, matching real desktop input and PARI init.
		val action = fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
		compose.runOnUiThread { action() }
		compose.waitForIdle()
	}
}
