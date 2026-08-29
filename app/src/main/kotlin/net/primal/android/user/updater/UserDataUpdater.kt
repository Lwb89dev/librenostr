package net.primal.android.user.updater

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.primal.android.user.repository.RelayRepository
import net.primal.android.user.repository.UserRepository
import net.primal.core.utils.Result
import net.primal.core.utils.runCatching
import net.primal.core.utils.updater.Updater
import net.primal.domain.profile.Nip05VerificationService
import net.primal.domain.profile.ProfileRepository

class UserDataUpdater @AssistedInject constructor(
    @Assisted val userId: String,
    private val userRepository: UserRepository,
    private val relayRepository: RelayRepository,
    private val profileRepository: ProfileRepository,
    private val nip05VerificationService: Nip05VerificationService,
) : Updater() {

    override suspend fun doUpdate(): Result<Unit> {
        coroutineScope {
            // Profile chain — user account fetched before profile lookup + eager verification.
            launch {
                runCatching { userRepository.fetchAndUpdateUserAccount(userId = userId) }
                runCatching {
                    val profile = profileRepository.findProfileDataOrNull(profileId = userId)
                    profile?.internetIdentifier?.let { identifier ->
                        nip05VerificationService.verifyEagerly(
                            pubkey = userId,
                            internetIdentifier = identifier,
                        )
                    }
                }
            }

            launch { runCatching { relayRepository.syncUserRelaysOrBootstrap(userId = userId) } }
        }

        return Result.success(Unit)
    }
}
