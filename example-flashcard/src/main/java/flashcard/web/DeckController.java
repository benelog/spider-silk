package flashcard.web;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import spidersilk.WebRequest;
import spidersilk.WebResponse;

import flashcard.service.CardService;
import flashcard.service.DeckService;
import flashcard.service.StudyDirection;

public class DeckController {

    private final DeckService deckService;
    private final CardService cardService;

    public DeckController(DeckService deckService, CardService cardService) {
        this.deckService = deckService;
        this.cardService = cardService;
    }


    public WebResponse createDeck(WebRequest req) {
        return WebResponse.redirect("/decks/" + deckService.createDeck(req.param("name")).id());
    }

    public WebResponse showDeck(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        Map<String, Object> model = new HashMap<>();
        model.put("deck", deckService.getDeck(deckId));
        model.put("cards", cardService.cardsWithTags(deckId));
        model.put("directions", StudyDirection.values());
        model.put("message", req.flashed("message"));
        model.put("error", req.flashed("error"));
        return WebResponse.render("deck.jte", model);
    }

    public WebResponse renameDeck(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        deckService.renameDeck(deckId, req.param("name"));
        return WebResponse.redirect("/decks/" + deckId);
    }

    public WebResponse deleteDeck(WebRequest req) {
        deckService.deleteDeck(req.pathParamLong("deckId"));
        return WebResponse.redirect("/");
    }

    public WebResponse addCard(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        cardService.addCard(deckId, req.param("text"), req.param("meaning"),
                req.param("tags", ""));
        return WebResponse.redirect("/decks/" + deckId);
    }

    public WebResponse editCardForm(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        long cardId = req.pathParamLong("cardId");
        Map<String, Object> model = new HashMap<>();
        model.put("deck", deckService.getDeck(deckId));
        model.put("card", cardService.getCard(cardId));
        model.put("tags", String.join(", ", cardService.tagsOf(cardId)));
        return WebResponse.render("card-edit.jte", model);
    }

    public WebResponse editCard(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        cardService.editCard(req.pathParamLong("cardId"), req.param("text"),
                req.param("meaning"), req.param("tags", ""));
        return WebResponse.redirect("/decks/" + deckId);
    }

    public WebResponse deleteCard(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        cardService.deleteCard(req.pathParamLong("cardId"));
        return WebResponse.redirect("/decks/" + deckId);
    }

    public WebResponse exportCsv(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        String csv = deckService.exportCsv(deckId);
        return WebResponse
                .bytes(csv.getBytes(StandardCharsets.UTF_8), "text/csv; charset=UTF-8")
                .attachment("deck-%d.csv".formatted(deckId));
    }

    public WebResponse importCsv(WebRequest req) {
        long deckId = req.pathParamLong("deckId");
        int imported = deckService.importCsv(deckId, req.file("file").asText());
        req.flash("message", "Imported " + imported + " cards.");
        return WebResponse.redirect("/decks/" + deckId);
    }
}
