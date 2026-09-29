package net.primal.data.local.dao.decks

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity
data class DeckData(
    @PrimaryKey val id: String,
    val ownerId: String,
    val name: String,
    val position: Int = 0,
)
