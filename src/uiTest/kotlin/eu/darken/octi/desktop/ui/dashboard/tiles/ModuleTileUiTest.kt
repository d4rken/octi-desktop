@file:OptIn(ExperimentalTestApi::class)

package eu.darken.octi.desktop.ui.dashboard.tiles

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import eu.darken.octi.desktop.protocol.modules.clipboard.ClipboardInfo
import eu.darken.octi.desktop.ui.dashboard.ModuleState
import eu.darken.octi.desktop.ui.setOctiContent
import io.kotest.matchers.shouldBe
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Test

/**
 * [ClipboardTile] is a pure composable (state + callback), so these exercise the harness with no
 * AppGraph: render, state-variant branching, click dispatch.
 */
class ModuleTileUiTest {

    @Test
    fun `renders the module header`() = runComposeUiTest {
        setOctiContent {
            ClipboardTile(state = ModuleState.NotFound, onClick = {})
        }
        onNodeWithText("Clipboard").assertIsDisplayed()
    }

    @Test
    fun `NotFound shows the empty-state label`() = runComposeUiTest {
        // Clipboard uses NotFoundPolicy.EMPTY_STATE → "Empty" (not "Not shared").
        setOctiContent {
            ClipboardTile(state = ModuleState.NotFound, onClick = {})
        }
        onNodeWithText("Empty").assertIsDisplayed()
    }

    @Test
    fun `Ok with simple text shows the Text body`() = runComposeUiTest {
        val info = ClipboardInfo(type = ClipboardInfo.Type.SIMPLE_TEXT, data = "hello".encodeUtf8())
        setOctiContent {
            ClipboardTile(state = ModuleState.Ok(info), onClick = {})
        }
        onNodeWithText("Text").assertIsDisplayed()
    }

    @Test
    fun `clicking the card fires onClick`() = runComposeUiTest {
        var clicks = 0
        // NotFound has no copy IconButton, so the card is the only clickable node.
        setOctiContent {
            ClipboardTile(state = ModuleState.NotFound, onClick = { clicks++ })
        }
        onNode(hasClickAction()).performClick()
        clicks shouldBe 1
    }
}
