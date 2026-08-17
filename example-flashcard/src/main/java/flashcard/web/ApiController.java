package flashcard.web;

import spidersilk.App;
import spidersilk.WebContext;

import flashcard.domain.Deck;
import flashcard.service.CardService;
import flashcard.service.DeckService;

/**
 * A JSON API on top of the same service layer.
 * The wire format lives in {@link Codecs} as hand-written writers and readers
 * (no automatic serialization), so a handler is one line.
 */
public class ApiController implements Controller {

    private final DeckService deckService;
    private final CardService cardService;

    public ApiController(DeckService deckService, CardService cardService) {
        this.deckService = deckService;
        this.cardService = cardService;
    }

    @Override
    public void register(App app) {
        app.path("/api/decks", decks -> {
            decks.get("", this::listDecks);
            decks.post("", this::createDeck);
            decks.get("/{deckId}/cards", this::listCards);
        });
    }

    void listDecks(WebContext ctx) {
        ctx.json(deckService.deckSummaries(), Codecs.DECK_SUMMARIES);
    }

    void createDeck(WebContext ctx) {
        Deck deck = deckService.createDeck(ctx.bodyJson(Codecs.NEW_DECK).name());
        ctx.status(201)
                .header("Location", "/api/decks/" + deck.id())
                .json(deck, Codecs.DECK);
    }

    void listCards(WebContext ctx) {
        long deckId = ctx.pathParamLong("deckId");
        deckService.getDeck(deckId);   // IllegalArgumentException -> 404 when missing
        ctx.json(cardService.cardsWithTags(deckId), Codecs.CARDS);
    }
}
