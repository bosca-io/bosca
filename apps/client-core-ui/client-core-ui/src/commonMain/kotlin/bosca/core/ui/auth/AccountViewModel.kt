package bosca.core.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bosca.core.security.BoscaAuth
import bosca.core.security.model.Group
import bosca.core.security.model.Profile
import bosca.core.security.model.ProfileInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * ViewModel for authenticated account & profile management (change
 * password/identifier, multi-profile management, groups). Thin wrapper over
 * [BoscaAuth]; reactive profile/group state is read straight from the client.
 */
class AccountViewModel(private val auth: BoscaAuth) : ViewModel() {

    val profiles: StateFlow<List<Profile>> = auth.profiles
    val currentProfile: StateFlow<Profile?> = auth.currentProfile
    val groups: StateFlow<List<Group>> = auth.groups

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _events = MutableSharedFlow<AccountEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AccountEvent> = _events.asSharedFlow()

    fun changePassword(newPassword: String, oldPassword: String) =
        launchGuarded { auth.changePassword(newPassword, oldPassword); _events.emit(AccountEvent.PasswordChanged) }

    fun changeIdentifier(identifier: String, password: String) =
        launchGuarded { auth.changeIdentifier(identifier, password); _events.emit(AccountEvent.IdentifierChanged) }

    fun refreshProfiles() = launchGuarded { auth.getProfiles() }

    fun updateProfile(id: Uuid?, input: ProfileInput) =
        launchGuarded { auth.updateProfile(id, input); _events.emit(AccountEvent.ProfileSaved) }

    fun setPrimaryProfile(profileId: Uuid) =
        launchGuarded { auth.setPrimaryProfile(profileId); _events.emit(AccountEvent.PrimaryProfileChanged) }

    fun refreshGroups() = launchGuarded { auth.getGroups() }

    fun logout() = launchGuarded { auth.signOut(); _events.emit(AccountEvent.SignedOut) }

    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                block()
            } catch (e: Throwable) {
                _events.emit(AccountEvent.Error(e.message ?: "Operation failed"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    sealed class AccountEvent {
        data object PasswordChanged : AccountEvent()
        data object IdentifierChanged : AccountEvent()
        data object ProfileSaved : AccountEvent()
        data object PrimaryProfileChanged : AccountEvent()
        data object SignedOut : AccountEvent()
        data class Error(val message: String) : AccountEvent()
    }
}
