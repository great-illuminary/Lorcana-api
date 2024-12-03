package eu.codlab.lorcana.dreamborn

import eu.codlab.lorcana.dreamborn.database.Deck
import eu.codlab.lorcana.dreamborn.database.DeckCards
import eu.codlab.lorcana.dreamborn.database.LocalDatabase
import eu.codlab.lorcana.dreamborn.decks.DeckDescriptor
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.utils.io.CancellationException
import korlibs.time.DateTime
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

class Dreamborn {
    private val database = LocalDatabase.create()
    private val api = DreambornApi()

    private suspend fun loadDecks() = api.decks("trending", "EUR")

    private suspend fun deckFromRemote(deckId: String) = api.deck(deckId, "EUR")

    fun decks() = database.localDecksController.selectAllWithCards()

    fun deck(deck: String) = database.localDecksController.selectedFromUuidWithCards(deck)

    fun deckFromCreator(creator: String) = database.localDecksController.selectedFromCreator(creator)

    suspend fun track(creator: String) {
        val profile = api.creator(creator)

        val found = database.localCreatorsController.selectAllCreators()
            .firstOrNull { it.uuid == creator }

        if (null != found) {
            database.localCreatorsController.update(
                id = found.id,
                name = profile.displayName,
                tracking = true,
                youtube = profile.profiles.youtube
            )
        } else {
            database.localCreatorsController.insert(
                uuid = creator,
                name = profile.displayName,
                tracking = true,
                youtube = profile.profiles.youtube
            )
        }
    }

    suspend fun checkDecks(): List<Deck> {
        val decksFromRemote = loadDecks()

        decksFromRemote.forEach { deckFromRemote ->
            checkRemoteDeck(deckFromRemote, false)
        }

        val creators = database.localCreatorsController.selectAllCreators()
            .filter { it.tracking }

        creators.forEach { creator ->
            try {
                api.creator(creator.uuid)
                    .decks.forEach { checkRemoteDeck(it, false) }
            } catch (err: Throwable) {
                err.printStackTrace()
            }
        }

        decks().forEach {
            // now check every deck that are known to see if we have an update available...
            checkUpdateFromDeck(it, lastChecked = DateTime.now())

            delay(10.seconds)
        }

        // and refetch the decks
        return decks()
    }

    private suspend fun checkRemoteDeck(deckFromRemote: DeckDescriptor, fromTrending: Boolean) {
        println("checkRemoteDeck ${deckFromRemote.name}")
        val fromDatabase = database.localDecksController.selectedFromUuid(deckFromRemote.id)

        println("${fromDatabase?.updatedAt} vs ${deckFromRemote.lastUpdated}")

        if (null != fromDatabase && fromDatabase.updatedAt == deckFromRemote.lastUpdated) {
            println("no need to even check this deck, last update is the same")
            return
        }

        val actualDeck = deckFromRemote(deckFromRemote.id) ?: return

        val lastChecked = DateTime.now()

        if (null == fromDatabase) {
            println("The deck is not in the database")

            database.localDecksController.insert(
                deckFromRemote,
                actualDeck.cards,
                DateTime.now(),
                lastCheckedAt = lastChecked,
                lastTrendingAt = if (fromTrending) lastChecked else null
            )
            return
        }

        checkUpdateFromDeck(fromDatabase, actualDeck, lastChecked, fromTrending)
    }

    private suspend fun checkUpdateFromDeck(
        fromDatabase: Deck,
        lastChecked: DateTime = DateTime.now()
    ) {
        println("TODO: Update disabled for now due to possible issues with data")
        return

        println("checkUpdateFromDeck ${fromDatabase.name}")
        val actualDeck = deckFromRemote(fromDatabase.uuid) ?: return

        checkUpdateFromDeck(fromDatabase, actualDeck, lastChecked)
    }

    private fun checkUpdateFromDeck(
        fromDatabase: Deck,
        actualDeck: DeckDescriptor,
        lastChecked: DateTime = DateTime.now(),
        inTrending: Boolean = false
    ) {
        println("  -> check any update")
        database.localDecksController.updateLastCheckedAt(fromDatabase, lastChecked)

        if (inTrending) {
            database.localDecksController.updateLastTrendingAt(fromDatabase, lastChecked)
        }

        if (actualDeck.lastUpdated != fromDatabase.updatedAt) {
            println("The deck needs an update -> ${actualDeck.lastUpdated} != ${fromDatabase.updatedAt}")
            database.localDecksController.update(
                fromDatabase.copyUpdateFromRemote(actualDeck),
                actualDeck.cards,
                updatedAt = DateTime.now()
            )
        }
    }
}