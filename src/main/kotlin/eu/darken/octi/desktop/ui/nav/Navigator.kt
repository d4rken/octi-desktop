package eu.darken.octi.desktop.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the active screen. Pushed/popped via [navigateTo] and [pop]. A
 * single-entry back stack is enough for the MVP — the dashboard is the natural home and
 * detail/settings screens never stack more than one level deep.
 *
 * If we later need true multi-level history (e.g. dashboard → device → file → device), promote
 * the underlying list to a proper deque; the API doesn't need to change.
 */
class Navigator(initial: Screen = Screen.Linking) {

    private val _current = MutableStateFlow(initial)
    val current: StateFlow<Screen> = _current.asStateFlow()

    private val backStack = ArrayDeque<Screen>()

    fun navigateTo(screen: Screen, clearStack: Boolean = false) {
        if (clearStack) {
            backStack.clear()
        } else if (_current.value != screen) {
            backStack.addLast(_current.value)
        }
        _current.value = screen
    }

    /** Pop the top of the back stack. Returns true if a pop happened. */
    fun pop(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        _current.value = previous
        return true
    }

    fun replace(screen: Screen) {
        _current.value = screen
    }
}
