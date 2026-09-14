package net.primal.android.user.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RelayKind {
    UserRelay,

    // Nothing writes this anymore (the NWC-relay write path was removed from
    // WalletSettingsViewModel/UserRepository well before this enum was last touched), but Room
    // persists this value by name in the `relays` table, so removing the constant would throw on
    // deserialization for any install that still has a leftover row from when it was live.
    NwcRelay,
}
