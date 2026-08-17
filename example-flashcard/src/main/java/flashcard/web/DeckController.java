package flashcard.web;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import spidersilk.App;
import spidersilk.WebContext;

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

    void createDeck(WebContext ctx) {
        ctx.redirect("/decks/" + deckService.createDeck(ctx.param("name")).id());
    }

    void showDeck(WebContext ctx) {
        long deckId = ctx.pathParamLong("deckId");
        Map<String, Object> model = new HashMap<>();
        model.put("deck", deckService.getDeck(deckId));
        model.put("cards", cardService.cardsWithTags(deckId));
        model.put("directions", StudyDirection.values());
        model.put("message", ctx.flashed("message"));
        model.put("error", ctx.flashed("error"));
        ctx.render("deck.jte", model);
    }

    void renameDeck(WebContext ctx) {
        long deckId = ctx.pathParamLong("deckId");
        deckService.renameDeck(deckId, ctx.param("name"));
        ctx.redirect("/decks/" + deckId);
    }

    void deleteDeck(WebContext ctx) {
        deckService.deleteDeck(ctx.pathParamLong("deckId"));
        ctx.redirect("/");
    }

    void addCard(WebContext ctx) {
        long deckId = ctx.pathParamLong("deckId");
        cardService.addCard(deckId, ctx.param("text"), ctx.param("meaning"),
                ctx.param("tags", ""));
        ctx.redirect("/decks/" + deckId);
    }

    void editCardForm(WebContext ctx) {
        long deckId = ctx.pathParamLong("deckId");
        long cardId = ctx.pathParamLong("cardId");
        Map<String, Object> model = new HashMap<>();
        model.put("deck", deckService.getDeck(deckId));
        model.put("card", cardService.getCard(cardId));
        model.put("tags", String.join(", ", cardService.tagsOf(cardId)));
        ctx.render("card-edit.jte", model);
    }

    void editCard(WebContext ctx) {
        long deckId = ctx.pathParamLong("deckId");
        cardService.editCard(ctx.pathParamLong("cardId"), ctx.param("text"),
                ctx.param("meaning"), ctx.param("tags", ""));
        ctx.redirect("/decks/" + deckId);
    }

    void deleteCard(WebContext ctx) {
        long deckId = ctx.pathParamLong("deckId");
        cardService.deleteCard(ctx.pathParamLong("cardId"));
        ctx.redirect("/decks/" + deckId);
    }

    void exportCsv(WebContext ctx) {
        long deckId = ctx.pathParamLong("deckId");
        String csv = deckService.exportCsv(deckId);
        ctx.attachment("deck-%d.csv".formatted(deckId))
                .bytes(csv.getBytes(StandardCharsets.UTF_8), "text/csv; charset=UTF-8");
    }

    void importCsv(WebContext ctx) {
        long deckId = ctx.pathParamLong("deckId");
        int imported = deckService.importCsv(deckId, ctx.file("file").asText());
        ctx.flash("message", "Imported " + imported + " cards.");
        ctx.redirect("/decks/" + deckId);
    }
}
