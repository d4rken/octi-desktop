package eu.darken.octi.desktop.ui.linking

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.linking.LinkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val TAG = logTag("UI", "Linking")

/**
 * Holds the Linking screen's UI state and drives the [eu.darken.octi.desktop.linking.LinkController]
 * call. Not a Compose `ViewModel`-the-class — those are mobile-Android-specific; this is just a
 * plain holder with its own [StateFlow]. Lifetime is the duration of the Linking screen; the
 * coroutine is launched on [AppGraph.appScope] so a navigation away won't cancel an in-flight
 * link round-trip mid-rollback.
 */
class LinkingViewModel(private val graph: AppGraph) {

    private val _state = MutableStateFlow<LinkingUiState>(LinkingUiState.Idle)
    val state: StateFlow<LinkingUiState> = _state.asStateFlow()

    /** User pressed the "Link" button. Validates locally first, then talks to the server. */
    fun submit(rawCode: String) {
        if (_state.value is LinkingUiState.Working) return
        _state.value = LinkingUiState.Working
        graph.appScope.launch {
            val result = try {
                graph.linkController.link(rawCode, graph.deviceId)
            } catch (e: Throwable) {
                log(TAG, Logging.Priority.ERROR, e) { "Unexpected link() failure" }
                LinkResult.NetworkError(e)
            }
            handleResult(result)
        }
    }

    private fun handleResult(result: LinkResult) {
        _state.value = when (result) {
            LinkResult.Success -> {
                graph.onLinked()
                LinkingUiState.Idle
            }
            LinkResult.InvalidBase64 -> LinkingUiState.Error(
                "That doesn't look like a valid Octi link code. Make sure you copied the whole string from your phone.",
            )
            LinkResult.InvalidGzip -> LinkingUiState.Error(
                "Link code couldn't be decoded. The text might have been truncated or pasted with extra characters.",
            )
            is LinkResult.InvalidJson -> LinkingUiState.Error(
                "Link code is malformed. Generate a new one on your phone and try again.",
            )
            is LinkResult.InvalidKeyset -> LinkingUiState.Error(
                "Encryption keyset in the link code is unreadable. Generate a new code on your phone.",
            )
            LinkResult.ShareCodeExpiredOrConsumed -> LinkingUiState.Error(
                "Link code expired or already used (codes are valid for 60 minutes). Generate a new one on your phone.",
            )
            is LinkResult.NetworkError -> LinkingUiState.Error(
                "Couldn't reach the Octi server: ${result.cause.message ?: result.cause.javaClass.simpleName}.",
            )
            is LinkResult.KeystoreFailureRolledBack -> LinkingUiState.Error(
                "Saved server registration but couldn't store credentials locally. Server was cleaned up — try again.",
            )
            is LinkResult.OrphanedDevice -> LinkingUiState.Error(
                "Server registered this device but local save AND rollback both failed. " +
                    "Open Settings on a working device and remove the orphan.",
            )
        }
    }

    fun dismissError() {
        if (_state.value is LinkingUiState.Error) _state.value = LinkingUiState.Idle
    }
}

sealed class LinkingUiState {
    data object Idle : LinkingUiState()
    data object Working : LinkingUiState()
    data class Error(val message: String) : LinkingUiState()
}
