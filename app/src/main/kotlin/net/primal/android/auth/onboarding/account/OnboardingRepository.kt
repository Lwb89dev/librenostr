package net.primal.android.auth.onboarding.account

import javax.inject.Inject
import net.primal.android.auth.onboarding.account.ui.model.OnboardingFollowPack
import net.primal.android.networking.relays.FALLBACK_RELAY_URLS

class OnboardingRepository @Inject constructor(
) {

    suspend fun fetchDefaultRelays(): List<String> = FALLBACK_RELAY_URLS

    /** Follow packs used to come from a centralized Primal endpoint. Onboarding remains
     * fully usable without that service; users can add follows from relays after signing in. */
    suspend fun fetchFollowPacks(): List<OnboardingFollowPack> = emptyList()
}
