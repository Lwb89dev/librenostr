package net.primal.android.deck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.primal.domain.profile.ProfileData
import net.primal.domain.profile.ProfileRepository

@HiltViewModel(assistedFactory = DeckProfileViewModel.Factory::class)
class DeckProfileViewModel @AssistedInject constructor(
    @Assisted profileId: String,
    profileRepository: ProfileRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(profileId: String): DeckProfileViewModel
    }

    private val _profile = MutableStateFlow<ProfileData?>(null)
    val profile = _profile.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepository.observeProfileData(profileId = profileId).collect { _profile.value = it }
        }
    }
}
