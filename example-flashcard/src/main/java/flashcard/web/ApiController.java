package flashcard.web;

import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;

import flashcard.domain.Deck;
import flashcard.service.CardService;
import flashcard.service.DeckService;

/**
 * A JSON API on top of the same service layer.
 * The wire format lives in {@link Codecs} as hand-written writers and readers
 * (no automatic serialization), so a handler is one line.
 */
public class ApiController {

    private final DeckService deckService;
    private final CardService cardService;

    public ApiController(DeckService deckService, CardService cardService) {
        this.deckService = deckService;
        this.cardService = cardService;
    }


    public WebResponse listDecks(WebRequest req) {
        return WebResponse.json(deckService.deckSummaries(), Codecs.DECK_SUMMARIES);
    }

    public WebResponse createDeck(WebRequest req) {
        Deck deck = deckService.createDeck(req.bodyJson(Codecs.NEW_DECK).name());
        return WebResponse.json(deck, Codecs.DECK)
                .status(HttpStatus.CREATED)
                .header("Location", "/api/decks/" + deck.id());
    }

    public WebResponse listCards(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        deckService.getDeck(deckId);   // IllegalArgumentException -> 404 when missing
        return WebResponse.json(cardService.cardsWithTags(deckId), Codecs.CARDS);
    }

    /**
     * The same cards as NDJSON, a line at a time. Nothing here holds the deck:
     * the service hands over one card, the sink writes it, and the row is gone
     * before the next arrives.
     *
     * <p>The deck is looked up before the response is returned, because that is
     * the last moment a missing one can still answer 404 — once the stream
     * starts, the headers are committed.
     */
    public WebResponse exportCards(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        deckService.getDeck(deckId);
        return WebResponse
                .ndjson(sink -> cardService.eachCard(deckId,
                        card -> sink.write(card, Codecs.CARD_ROW)))
                .attachment("deck-%d.ndjson".formatted(deckId));
    }

    /**
     * The import side, read the same way: {@code req.bodyNdjson} is lazy, so the
     * upload is parsed as the inserts consume it. A line the reader rejects
     * answers 400 naming the line, and rolls the import back.
     */
    public WebResponse importCards(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        deckService.getDeck(deckId);
        int imported = cardService.importCards(deckId, req.bodyNdjson(Codecs.CARD_DRAFT));
        return WebResponse.json(Json.obj().put("imported", imported));
    }
}
