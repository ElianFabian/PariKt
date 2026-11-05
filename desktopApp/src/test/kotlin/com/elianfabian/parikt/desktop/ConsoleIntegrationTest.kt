package com.elianfabian.parikt.desktop

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
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

@OptIn(ExperimentalTestApi::class)
class ConsoleIntegrationTest {
	@get:Rule val compose = createComposeRule()

	@After fun closeEngine() { compose.runOnUiThread { ConsoleSession.close() } }

	@Test fun realEngineStartupExamplesAndCommandEntry() {
		compose.setContent { Box(Modifier.size(400.dp, 720.dp)) { ConsoleApp(persistPreferences = false) } }
		compose.onNodeWithTag("engine-status").assertContentDescriptionContains("Ready", substring = true)
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
		// Plain Enter must insert a newline, never evaluate or clear the draft.
		compose.onNodeWithTag("command-input").performTextReplacement("{")
		compose.onNodeWithTag("command-input").performKeyInput { pressKey(Key.Enter) }
		compose.onNodeWithTag("command-input").performTextInput("12^2")
		compose.onNodeWithTag("command-input").performKeyInput { pressKey(Key.Enter) }
		compose.onNodeWithTag("command-input").performTextInput("}")
		compose.onNodeWithTag("command-input").assertTextContains("{\n12^2\n}")
		compose.onNodeWithTag("command-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Default))
		compose.onNodeWithTag("command-input").assertTextContains("{\n12^2\n}")
		capture("multiline")
		compose.onNodeWithTag("run-command").clickOnUiThread()
		compose.onNodeWithText("%4 = 144", substring = true).assertExists()

		openMenuItem("Custom scripts")
		compose.onNodeWithTag("scripts-dialog").assertExists()
		compose.onNodeWithTag("script-name").performTextInput("Square")
		compose.onNodeWithTag("script-code").performTextInput("sq(x)=x^2")
		compose.onNodeWithTag("save-script").clickOnUiThread()
		compose.onNodeWithText("Square").assertExists()
		capture("scripts", dialog = true)
		compose.onNodeWithTag("close-scripts").clickOnUiThread()
		compose.onNodeWithTag("scripts-dialog").assertDoesNotExist()
		openMenuItem("Custom scripts")
		compose.onNodeWithText("Square").assertExists()
		compose.onNodeWithTag("edit-script-Square").clickOnUiThread()
		compose.onNodeWithTag("script-name").performTextReplacement("Square function")
		compose.onNodeWithTag("save-script").clickOnUiThread()
		compose.onNodeWithTag("load-script-Square function").clickOnUiThread()
		compose.onNodeWithTag("scripts-dialog").assertDoesNotExist()
		compose.onNodeWithTag("command-input").assertTextContains("sq(x)=x^2")
		openMenuItem("Custom scripts")
		compose.onNodeWithTag("run-script-Square function").clickOnUiThread()
		compose.onNodeWithTag("scripts-dialog").assertDoesNotExist()
		compose.onNodeWithTag("command-input").performTextReplacement("sq(12)")
		compose.onNodeWithTag("run-command").clickOnUiThread()
		capture("script-result")
		compose.onNodeWithText("%6 = 144", substring = true).assertExists()
		compose.onNodeWithText("%6 = 144", substring = true).performTouchInput { swipeRight() }
		compose.onNodeWithText("%6 = 144", substring = true).assertDoesNotExist()
		openMenuItem("Custom scripts")
		compose.onNodeWithTag("delete-script-Square function").clickOnUiThread()
		compose.onNodeWithText("Delete", substring = false).clickOnUiThread()
		compose.onNodeWithText("Square function").assertDoesNotExist()
		compose.onNodeWithTag("close-scripts").clickOnUiThread()

		openMenuItem("Settings")
		capture("settings")
		compose.onNodeWithTag("stack-size").performTextReplacement("8000000")
		compose.onNodeWithTag("maximum-stack-size").performTextReplacement("16000000")
		compose.onNodeWithTag("apply-settings").clickOnUiThread()
		compose.onNodeWithTag("engine-status").assertContentDescriptionContains("Ready", substring = true)
		compose.runOnUiThread {
			val maximum = ConsoleSession.evaluate("default(parisizemax)").substringAfterLast('=').trim().toLong()
			org.junit.Assert.assertTrue(maximum >= 16_000_000)
		}
	}

	private fun openMenuItem(text: String) {
		compose.onNodeWithTag("console-menu").clickOnUiThread()
		compose.onNodeWithText(text).clickOnUiThread()
	}

	private fun capture(name: String, dialog: Boolean = false) {
		val image = (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage()
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
