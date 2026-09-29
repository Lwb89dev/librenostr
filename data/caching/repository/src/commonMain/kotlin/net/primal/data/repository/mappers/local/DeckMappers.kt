package net.primal.data.repository.mappers.local

import net.primal.data.local.dao.decks.DeckColumnData
import net.primal.data.local.dao.decks.DeckData
import net.primal.domain.decks.Deck
import net.primal.domain.decks.DeckColumn

fun DeckData.asDeckDO(columns: List<DeckColumn>): Deck =
    Deck(
        id = this.id,
        ownerId = this.ownerId,
        name = this.name,
        position = this.position,
        columns = columns,
    )

fun DeckColumnData.asDeckColumnDO(): DeckColumn =
    DeckColumn(
        id = this.id,
        deckId = this.deckId,
        type = this.type,
        paramsJson = this.paramsJson,
        position = this.position,
    )
