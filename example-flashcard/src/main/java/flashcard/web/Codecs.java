package flashcard.web;

import java.util.List;

import spidersilk.json.Json;
import spidersilk.json.JsonReader;
import spidersilk.json.JsonWriter;

import flashcard.domain.CardWithTags;
import flashcard.domain.Deck;
import flashcard.domain.DeckSummary;

/**
 * The wire format of the JSON API, in one place.
 *
 * <p>These live in the web layer rather than on the records themselves: a codec
 * on {@link Deck} would make {@code flashcard.domain} import
 * {@code spidersilk.json}, so the domain would depend on the web framework to
 * state its own wire format. The tier that serves the JSON owns it.
 *
 * <p>Most of them are write-only — a deck summary goes out and never comes
 * back in — which is why they are {@code JsonWriter}s and not codecs.
 */
final class Codecs {

    private Codecs() {
    }

    /** The body of {@code POST /api/decks}. */
    record NewDeck(String name) {
    }

    static final JsonReader<NewDeck> NEW_DECK =
            json -> new NewDeck(json.asObject().getString("name"));

    static final JsonWriter<Deck> DECK = deck -> Json.obj()
            .put("id", deck.id())
            .put("name", deck.name());

    static final JsonWriter<DeckSummary> DECK_SUMMARY = summary -> Json.obj()
            .put("id", summary.id())
            .put("name", summary.name())
            .put("cardCount", summary.cardCount())
            .put("dueCount", summary.dueCount());

    static final JsonWriter<List<DeckSummary>> DECK_SUMMARIES = JsonWriter.list(DECK_SUMMARY);

    static final JsonWriter<CardWithTags> CARD = cardWithTags -> Json.obj()
            .put("id", cardWithTags.card().id())
            .put("text", cardWithTags.card().text())
            .put("meaning", cardWithTags.card().meaning())
            .put("tags", Json.arr().addAll(cardWithTags.tags()));

    static final JsonWriter<List<CardWithTags>> CARDS = JsonWriter.list(CARD);
}
