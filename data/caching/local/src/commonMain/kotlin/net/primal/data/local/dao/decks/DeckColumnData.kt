package net.primal.data.local.dao.decks

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import net.primal.domain.decks.DeckColumnType

@Entity(indices = [Index(value = ["deckId"])])
data class DeckColumnData(
    @PrimaryKey val id: String,
    val deckId: String,
    val type: DeckColumnType,
    val paramsJson: String = "{}",
    val position: Int = 0,
)
