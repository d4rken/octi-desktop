package eu.darken.octi.desktop.ui.linking

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.di.AppGraph
import eu.darken.octi.desktop.linking.CreateAccountResult
import eu.darken.octi.desktop.linking.LinkResult
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val TAG = logTag("UI", "Linking")

/**
 * Holds the Linking screen's UI state and drives both flows offered there: pasting an existing
 * link code ([submit]) and creating a brand-new account on a chosen server ([createAccount]).
 *
 * One [LinkingUiState] for both flows — only one is active at a time. The active [LinkingMode]
 * lives in [mode] and is flipped by the segment control in the UI.
 *
 * Not a Compose `ViewModel`-the-class — those are mobile-Android-specific; this is a plain
 * holder. The coroutine is launched on [AppGraph.appScope] so a navigation away won't cancel
 * an in-flight call mid-rollback.
 */
class LinkingViewModel(private val graph: AppGraph) {

    private val _state = MutableStateFlow<LinkingUiState>(LinkingUiState.Idle)
    val state: StateFlow<LinkingUiState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(LinkingMode.ExistingAccount)
    val mode: StateFlow<LinkingMode> = _mode.asStateFlow()

    fun setMode(next: LinkingMode) {
        if (_state.value is LinkingUiState.Working) return
        _mode.value = next
        if (_state.value is LinkingUiState.Error) _state.value = LinkingUiState.Idle
    }

    /** User pressed "Link this device". Validates locally first, then talks to the server. */
    fun submit(rawCode: String) {
        if (_state.value is LinkingUiState.Working) return
        _state.value = LinkingUiState.Working
        graph.appScope.launch {
            val result = try {
                graph.linkController.link(rawCode, graph.deviceId)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                log(TAG, Logging.Priority.ERROR, e) { "Unexpected link() failure" }
                LinkResult.NetworkError(e)
            }
            handleLinkResult(result)
        }
    }

    /**
     * User pressed "Create new account". [serverUrlInput] is the inline URL field value — blank
     * means "use the production server". On parse error we surface the [InvalidServerAddress]
     * message inline rather than silently falling back.
     */
    fun createAccount(serverUrlInput: String) {
        if (_state.value is LinkingUiState.Working) return
        val address = resolveAddress(serverUrlInput).getOrElse { cause ->
            _state.value = LinkingUiState.Error(
                "Server URL is invalid: ${cause.message ?: cause.javaClass.simpleName}.",
            )
            return
        }
        _state.value = LinkingUiState.Working
        graph.appScope.launch {
            // Persist the user's choice (or clear when blank) inside the coroutine so a settings
            // write failure doesn't strand the UI in Working state. `Settings.update` is
            // synchronous file I/O; wrap so we recover instead of crashing the flow.
            runCatching {
                graph.settings.update {
                    it.copy(createAccountServerUrl = serverUrlInput.trim().takeIf { raw -> raw.isNotEmpty() })
                }
            }.onFailure { settingsCause ->
                if (settingsCause is kotlinx.coroutines.CancellationException) throw settingsCause
                log(TAG, Logging.Priority.WARN, settingsCause) { "Settings persist failed; continuing with create-account anyway" }
            }
            val result = try {
                graph.linkController.createAccount(graph.deviceId, address)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                log(TAG, Logging.Priority.ERROR, e) { "Unexpected createAccount() failure" }
                CreateAccountResult.NetworkError(e)
            }
            handleCreateResult(result)
        }
    }

    private fun resolveAddress(input: String): Result<OctiServer.Address> {
        val trimmed = input.trim()
        return if (trimmed.isEmpty()) {
            Result.success(OctiServer.Official.PROD.address)
        } else {
            OctiServer.Address.tryParse(trimmed)
        }
    }

    private fun handleLinkResult(result: LinkResult) {
        _state.value = when (result) {
            is LinkResult.Success -> {
                graph.onLinked(result.connectorId, result.credentials)
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
            is LinkResult.SettingsPersistFailedRolledBack -> LinkingUiState.Error(
                "Credentials saved but the local settings file couldn't be updated. Server was cleaned up — try again.",
            )
            is LinkResult.OrphanedDevice -> LinkingUiState.Error(
                "Server registered this device but local save AND rollback both failed. " +
                    "Open Settings on a working device and remove the orphan.",
            )
        }
    }

    private fun handleCreateResult(result: CreateAccountResult) {
        _state.value = when (result) {
            is CreateAccountResult.Success -> {
                graph.onLinked(result.connectorId, result.credentials)
                LinkingUiState.Idle
            }
            CreateAccountResult.DeviceAlreadyRegistered -> LinkingUiState.Error(
                "The server rejected the registration (HTTP 400). This usually means this device " +
                    "is already registered — open Octi on another linked device and remove this " +
                    "one from the device list, then try again. If the problem persists, the server " +
                    "may have rejected the request for a different reason.",
            )
            is CreateAccountResult.NetworkError -> LinkingUiState.Error(
                "Couldn't reach the Octi server: ${result.cause.message ?: result.cause.javaClass.simpleName}.",
            )
            is CreateAccountResult.KeystoreFailureRolledBack -> LinkingUiState.Error(
                "Created the account but couldn't store credentials locally. Server was cleaned up — try again.",
            )
            is CreateAccountResult.SettingsPersistFailedRolledBack -> LinkingUiState.Error(
                "Account created but the local settings file couldn't be updated. Server was cleaned up — try again.",
            )
            is CreateAccountResult.OrphanedAccount -> LinkingUiState.Error(
                "Server created the account but local save AND rollback both failed. " +
                    "The account exists on the server with no local access — contact the server admin.",
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

enum class LinkingMode { ExistingAccount, NewAccount }
