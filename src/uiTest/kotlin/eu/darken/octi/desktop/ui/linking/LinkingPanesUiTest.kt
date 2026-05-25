@file:OptIn(ExperimentalTestApi::class)

package eu.darken.octi.desktop.ui.linking

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import eu.darken.octi.desktop.ui.setOctiContent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The Linking panes are `internal` (widened from `private` for testing) stateless composables, so
 * the test owns the state: the "enabled after typing" case must hold `rawCode` in
 * `remember { mutableStateOf }` and feed the setter, or recomposition never happens and the
 * assertion is meaningless.
 */
class LinkingPanesUiTest {

    @Test
    fun `link button is disabled for a blank code`() = runComposeUiTest {
        setOctiContent {
            ExistingAccountPane(rawCode = "", onRawCodeChange = {}, onSubmit = {}, isWorking = false)
        }
        onNodeWithText("Link this device").assertIsNotEnabled()
    }

    @Test
    fun `link button is disabled for a whitespace-only code`() = runComposeUiTest {
        setOctiContent {
            ExistingAccountPane(rawCode = "   ", onRawCodeChange = {}, onSubmit = {}, isWorking = false)
        }
        onNodeWithText("Link this device").assertIsNotEnabled()
    }

    @Test
    fun `typing a code enables the button and submit fires`() = runComposeUiTest {
        var submitted = 0
        setOctiContent {
            var rawCode by remember { mutableStateOf("") }
            ExistingAccountPane(
                rawCode = rawCode,
                onRawCodeChange = { rawCode = it },
                onSubmit = { submitted++ },
                isWorking = false,
            )
        }
        onNodeWithText("Link this device").assertIsNotEnabled()
        onNode(hasSetTextAction()).performTextInput("CODE-123")
        onNodeWithText("Link this device").assertIsEnabled().performClick()
        submitted shouldBe 1
    }

    @Test
    fun `working state disables the button even with a non-blank code`() = runComposeUiTest {
        setOctiContent {
            ExistingAccountPane(rawCode = "CODE-123", onRawCodeChange = {}, onSubmit = {}, isWorking = true)
        }
        onNodeWithText("Link this device").assertIsNotEnabled()
    }

    @Test
    fun `new account pane renders and create fires`() = runComposeUiTest {
        var created = 0
        setOctiContent {
            NewAccountPane(serverUrl = "", onServerUrlChange = {}, onCreate = { created++ }, isWorking = false)
        }
        onNodeWithText("Create account").assertIsDisplayed().performClick()
        created shouldBe 1
    }
}
