package assistant.mobile.android

import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Toolbar, tab and sheet controls do not necessarily have a scrolling ancestor. */
internal fun SemanticsNodeInteraction.performScrollToIfScrollable(): SemanticsNodeInteraction {
    if (generateSequence(fetchSemanticsNode().parent) { it.parent }
            .any { it.config.contains(SemanticsActions.ScrollBy) }) performScrollTo()
    return this
}

/** Scroll submission above the password keyboard, then wait for the settled Today shell. */
internal fun ComposeTestRule.loginNativeClient(login: String, password: String, imeClosed: () -> Boolean) {
    onNodeWithTag("login_login").performScrollTo().performTextReplacement(login)
    onNodeWithTag("login_password").performScrollTo().performTextInput(password)
    onNodeWithTag("login_submit").performScrollTo().assertIsDisplayed().performClick()
    waitUntil(15_000) { onAllNodes(hasTestTag("screen_Today")).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("screen_Today").assertIsDisplayed()
    awaitNativeTabBar(imeClosed)
}

/** Login hides the keyboard asynchronously; wait for insets and shell composition before navigating. */
internal fun ComposeTestRule.awaitNativeTabBar(imeClosed: () -> Boolean) {
    waitUntil(10_000) {
        imeClosed() && onAllNodes(hasTestTag("native_tab_bar")).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
    onNodeWithTag("native_tab_bar").assertIsDisplayed()
}

/** Visibility flips before the hide animation has released the keyboard's occupied height. */
internal fun View.isImeClosed(): Boolean {
    val insets = ViewCompat.getRootWindowInsets(this) ?: return false
    return !insets.isVisible(WindowInsetsCompat.Type.ime()) && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0
}
