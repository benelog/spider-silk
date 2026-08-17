package flashcard.web;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import spidersilk.App;
import spidersilk.WebRequest;
import spidersilk.WebResponse;

import flashcard.service.CardService;
import flashcard.service.DeckService;
import flashcard.service.StudyDirection;

public class DeckController implements Controller {

    private final DeckService deckService;
    private final CardService cardService;

    public DeckController(DeckService deckService, CardService cardService) {
        this.deckService = deckService;
        this.cardService = cardService;
    }

    @Override
    public void register(App app) {
        app.post("/decks", this::createDeck);
        app.get("/decks/{deckId}", this::showDeck);
        app.post("/decks/{deckId}/rename", this::renameDeck);
        app.post("/decks/{deckId}/delete", this::deleteDeck);
        app.post("/decks/{deckId}/cards", this::addCard);
        app.get("/decks/{deckId}/cards/{cardId}/edit", this::editCardForm);
        app.post("/decks/{deckId}/cards/{cardId}/edit", this::editCard);
        app.post("/decks/{deckId}/cards/{cardId}/delete", this::deleteCard);
        app.get("/decks/{deckId}/export.csv", this::exportCsv);
        app.post("/decks/{deckId}/import", this::importCsv);
    }

    WebResponse createDeck(WebRequest req) {
        return WebResponse.redirect("/decks/" + deckService.createDeck(req.param("name")).id());
    }

    WebResponse showDeck(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        Map<String, Object> model = new HashMap<>();
        model.put("deck", deckService.getDeck(deckId));
        model.put("cards", cardService.cardsWithTags(deckId));
        model.put("directions", StudyDirection.values());
        model.put("message", req.flashed("message"));
        model.put("error", req.flashed("error"));
        return WebResponse.render("deck.jte", model);
    }

    WebResponse renameDeck(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        deckService.renameDeck(deckId, req.param("name"));
        return WebResponse.redirect("/decks/" + deckId);
    }

    WebResponse deleteDeck(WebRequest req) {
        deckService.deleteDeck(req.pathParamLong("deckId"));
        return WebResponse.redirect("/");
    }

    WebResponse addCard(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        cardService.addCard(deckId, req.param("text"), req.param("meaning"),
                req.param("tags", ""));
        return WebResponse.redirect("/decks/" + deckId);
    }

    WebResponse editCardForm(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        long cardId = req.pathParamLong("cardId");
        Map<String, Object> model = new HashMap<>();
        model.put("deck", deckService.getDeck(deckId));
        model.put("card", cardService.getCard(cardId));
        model.put("tags", String.join(", ", cardService.tagsOf(cardId)));
        return WebResponse.render("card-edit.jte", model);
    }

    WebResponse editCard(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        cardService.editCard(req.pathParamLong("cardId"), req.param("text"),
                req.param("meaning"), req.param("tags", ""));
        return WebResponse.redirect("/decks/" + deckId);
    }

    WebResponse deleteCard(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        cardService.deleteCard(req.pathParamLong("cardId"));
        return WebResponse.redirect("/decks/" + deckId);
    }

    WebResponse exportCsv(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        String csv = deckService.exportCsv(deckId);
        return WebResponse
                .bytes(csv.getBytes(StandardCharsets.UTF_8), "text/csv; charset=UTF-8")
                .attachment("deck-%d.csv".formatted(deckId));
    }

    WebResponse importCsv(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        int imported = deckService.importCsv(deckId, req.file("file").asText());
        req.flash("message", "Imported " + imported + " cards.");
        return WebResponse.redirect("/decks/" + deckId);
    }
}
