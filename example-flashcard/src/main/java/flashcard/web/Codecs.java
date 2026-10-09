package flashcard.web;

import java.util.List;

import net.benelog.silkjson.JsonBound;
import net.benelog.silkjson.JsonCodec;
import net.benelog.silkjson.JsonReader;
import net.benelog.silkjson.JsonWriter;

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
 * {@code net.benelog.silkjson}, so the domain would depend on the web framework to
 * state its own wire format. The tier that serves the JSON owns it.
 *
 * <p>Most of them are write-only — a deck summary goes out and never comes
 * back in — which is why they are {@code JsonWriter}s and not codecs.
 *
 * <p>Two of them are generated rather than written: {@link NewDeck} carries
 * {@code @JsonBound} itself, and {@link DeckSummary}, a domain record that
 * carries nothing, is bound through the {@link DeckSummaryWire} mixin below.
 * Either way the codec is a constant handed to the handler, as the
 * hand-written ones are.
 */
final class Codecs {

    private Codecs() {
    }

    /** The body of {@code POST /api/decks}: its codec, {@code NewDeckJson}, is generated from the record. */
    @JsonBound
    record NewDeck(String name) {
    }

    static final JsonReader<NewDeck> NEW_DECK = CodecsNewDeckJson.CODEC;

    /**
     * The wire format of a deck summary, stated here in the web tier rather
     * than on the domain record: the mixin names the type, and would carry a
     * {@code @JsonbProperty} for any member whose name on the wire differs.
     */
    @JsonBound(DeckSummary.class)
    interface DeckSummaryWire {
    }

    static final JsonCodec<DeckSummary> DECK_SUMMARY = DeckSummaryJson.CODEC;

    static final JsonWriter<Deck> DECK = (deck, out) -> out.object()
            .put("id", deck.id())
            .put("name", deck.name())
            .end();

    static final JsonWriter<List<DeckSummary>> DECK_SUMMARIES = JsonWriter.list(DECK_SUMMARY);

    static final JsonWriter<CardWithTags> CARD = (cardWithTags, out) -> out.object()
            .put("id", cardWithTags.card().id())
            .put("text", cardWithTags.card().text())
            .put("meaning", cardWithTags.card().meaning())
            .array("tags").values(cardWithTags.tags()).end()
            .end();

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
    static final JsonWriter<Card> CARD_ROW = (card, out) -> out.object()
            .put("id", card.id())
            .put("text", card.text())
            .put("meaning", card.meaning())
            .put("createdAt", card.createdAt().toString())
            .end();

    /**
     * One line of the NDJSON import. {@code meaning} and {@code tags} are
     * optional, so a line carrying only {@code text} is a whole card.
     */
    static final JsonReader<CardDraft> CARD_DRAFT = JsonReader.object(object -> new CardDraft(
            object.getString("text"),
            object.getString("meaning", ""),
            object.getString("tags", "")));
}
