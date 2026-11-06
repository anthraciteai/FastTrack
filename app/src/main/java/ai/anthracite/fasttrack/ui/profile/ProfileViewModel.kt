package ai.anthracite.fasttrack.ui.profile

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.health.GuidanceEngine
import ai.anthracite.fasttrack.data.local.UserProfile
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: UserProfile = UserProfile(),
    val targets: GuidanceEngine.Targets = GuidanceEngine.compute(UserProfile())
)

class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = getApplication<FastTrackApp>().repository

    val state: StateFlow<ProfileUiState> = repo.observeProfile().map { p ->
        val profile = p ?: UserProfile()
        ProfileUiState(profile, GuidanceEngine.compute(profile))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileUiState())

    init {
        // Ensure a row exists so first launch shows defaults.
        viewModelScope.launch { repo.getProfile() }
    }

    fun save(profile: UserProfile) = viewModelScope.launch { repo.saveProfile(profile) }
}
