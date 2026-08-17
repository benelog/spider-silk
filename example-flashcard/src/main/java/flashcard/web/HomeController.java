package flashcard.web;

import java.util.HashMap;
import java.util.Map;

import spidersilk.App;
import spidersilk.WebContext;

import flashcard.service.DeckService;
import flashcard.service.SmartDeckService;
import flashcard.service.StudyDirection;
import flashcard.service.StudyService;

public class HomeController implements Controller {

    private final DeckService deckService;
    private final StudyService studyService;
    private final SmartDeckService smartDeckService;

    public HomeController(DeckService deckService, StudyService studyService,
                          SmartDeckService smartDeckService) {
        this.deckService = deckService;
        this.studyService = studyService;
        this.smartDeckService = smartDeckService;
    }

    @Override
    public void register(App app) {
        app.get("/", this::home);
    }

    void home(WebContext ctx) {
        Map<String, Object> model = new HashMap<>();
        model.put("todayCount", studyService.todayCount());
        model.put("oftenWrongCount", smartDeckService.oftenWrongCount());
        model.put("staleCount", smartDeckService.staleCount());
        model.put("decks", deckService.deckSummaries());
        model.put("smartDecks", smartDeckService.smartDecks());
        model.put("directions", StudyDirection.values());
        model.put("message", ctx.flashed("message"));
        model.put("error", ctx.flashed("error"));
        ctx.render("home.jte", model);
    }
}
