package flashcard.web;

import spidersilk.WebRequest;
import spidersilk.WebResponse;

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
                .status(201)
                .header("Location", "/api/decks/" + deck.id());
    }

    public WebResponse listCards(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        deckService.getDeck(deckId);   // IllegalArgumentException -> 404 when missing
        return WebResponse.json(cardService.cardsWithTags(deckId), Codecs.CARDS);
    }
}
