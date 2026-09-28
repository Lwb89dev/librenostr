package net.primal.domain.wot

/** How far a web-of-trust network computation has gotten, for the settings screen to show. */
sealed interface WotDiscoveryState {

    /** Never run, or the account has no follows to build a network from. */
    data object Idle : WotDiscoveryState

    data class Discovering(val fetchedFollowLists: Int, val totalFollows: Int) : WotDiscoveryState

    data class Complete(
        val firstDegreeCount: Int,
        val qualifiedCount: Int,
        val computedAtSeconds: Long,
    ) : WotDiscoveryState

    data class Failed(val reason: String) : WotDiscoveryState
}
