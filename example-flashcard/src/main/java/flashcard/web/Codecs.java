package flashcard.web;

import java.util.List;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonReader;
import net.benelog.spidersilk.json.JsonWriter;

import flashcard.domain.Card;
import flashcard.domain.CardWithTags;
import flashcard.domain.Deck;
import flashcard.domain.DeckSummary;
import flashcard.service.CardService.CardDraft;

/**
 * The wire format of the JSON API, in one place.
 *
 * <p>These live in the web layer rather than on the records themselves: a codec
 * on {@link Deck} would make {@code flashcard.domain} import
 * {@code net.benelog.spidersilk.json}, so the domain would depend on the web framework to
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

    /**
     * One line of the NDJSON export. It is the card itself and not
     * {@link #CARD}: an export streams a row at a time and cannot join the tags
     * of each without a query per card.
     *
     * <p>{@code createdAt} goes out as its ISO-8601 text. {@code Json} takes
     * strings, numbers, and booleans, so a date is a value the wire format
     * decides on — here, explicitly, rather than through whatever a library
     * would have picked.
     */
    static final JsonWriter<Card> CARD_ROW = card -> Json.obj()
            .put("id", card.id())
            .put("text", card.text())
            .put("meaning", card.meaning())
            .put("createdAt", card.createdAt().toString());

    /**
     * One line of the NDJSON import. {@code meaning} and {@code tags} are
     * optional, so a line carrying only {@code text} is a whole card.
     */
    static final JsonReader<CardDraft> CARD_DRAFT = json -> new CardDraft(
            json.asObject().getString("text"),
            json.asObject().optString("meaning", ""),
            json.asObject().optString("tags", ""));
}
