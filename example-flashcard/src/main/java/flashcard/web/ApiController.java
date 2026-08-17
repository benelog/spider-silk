package flashcard.web;

import steelspider.App;
import steelspider.WebContext;
import steelspider.HttpException;
import steelspider.json.Json;

import flashcard.domain.Deck;
import flashcard.service.CardService;
import flashcard.service.DeckService;

/**
 * A JSON API on top of the same service layer.
 * Responses are assembled explicitly with Steel Spider's Json builder
 * (no automatic serialization).
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
        app.get("/api/decks", this::listDecks);
        app.post("/api/decks", this::createDeck);
        app.get("/api/decks/{deckId}/cards", this::listCards);
    }

    void listDecks(WebContext ctx) {
        Json.JsonArray decks = Json.arr();
        for (var summary : deckService.deckSummaries()) {
            decks.add(Json.obj()
                    .put("id", summary.id())
                    .put("name", summary.name())
                    .put("cardCount", summary.cardCount())
                    .put("dueCount", summary.dueCount()));
        }
        ctx.json(decks);
    }

    void createDeck(WebContext ctx) {
        String name;
        try {
            name = ctx.bodyJson().asObject().getString("name");
        } catch (IllegalArgumentException e) {
            // A malformed body is a 400 (bad request), not a 404 (not found)
            throw new HttpException(400, e.getMessage());
        }
        Deck deck = deckService.createDeck(name);
        ctx.status(201)
                .header("Location", "/api/decks/" + deck.id())
                .json(Json.obj().put("id", deck.id()).put("name", deck.name()));
    }

    void listCards(WebContext ctx) {
        long deckId = ctx.pathParamLong("deckId");
        deckService.getDeck(deckId);   // IllegalArgumentException -> 404 when missing
        Json.JsonArray cards = Json.arr();
        for (var cardWithTags : cardService.cardsWithTags(deckId)) {
            cards.add(Json.obj()
                    .put("id", cardWithTags.card().id())
                    .put("text", cardWithTags.card().text())
                    .put("meaning", cardWithTags.card().meaning())
                    .put("tags", Json.arr().addAll(cardWithTags.tags())));
        }
        ctx.json(cards);
    }
}
