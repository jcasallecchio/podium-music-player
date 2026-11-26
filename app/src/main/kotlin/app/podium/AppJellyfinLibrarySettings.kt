package app.podium

import app.podium.core.common.Outcome
import app.podium.feature.settings.JellyfinLibrarySyncSettings
import app.podium.sources.api.AuthState
import app.podium.sources.jellyfin.JellyfinMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppJellyfinLibrarySettings(
    private val source: JellyfinMusicSource,
    private val scope: CoroutineScope,
) : JellyfinLibrarySyncSettings {
    override val canSync: StateFlow<Boolean> = source.auth!!.state
        .map { it is AuthState.SignedIn }
        .stateIn(scope, SharingStarted.Eagerly, source.auth!!.state.value is AuthState.SignedIn)
    private val mutableSyncing = MutableStateFlow(false)
    override val syncing: StateFlow<Boolean> = mutableSyncing.asStateFlow()
    private val mutableStatus = MutableStateFlow("Sync on first open")
    override val status: StateFlow<String> = mutableStatus.asStateFlow()

    override fun syncNow() {
        if (!canSync.value || mutableSyncing.value) return
        scope.launch(Dispatchers.IO) {
            mutableSyncing.value = true
            mutableStatus.value = "Syncing…"
            try {
                when (val result = source.syncNow()) {
                    is Outcome.Success -> mutableStatus.value = "${result.value} songs"
                    is Outcome.Failure -> mutableStatus.value = "Sync failed"
                }
            } finally {
                mutableSyncing.value = false
            }
        }
    }
}
