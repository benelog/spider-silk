package flashcard.web;

import java.util.HashMap;
import java.util.Map;

import spidersilk.Handler;
import spidersilk.WebRequest;
import spidersilk.WebResponse;

import flashcard.service.DeckService;
import flashcard.service.SmartDeckService;
import flashcard.service.StudyDirection;
import flashcard.service.StudyService;

/**
 * One route, so the class is the handler: it implements {@link Handler} and is
 * registered as itself, {@code app.get("/", context.homeAction())}.
 * A class that answers several routes keeps them as public methods instead —
 * {@link DeckController} is the other shape.
 */
public class HomeAction implements Handler {

    private final DeckService deckService;
    private final StudyService studyService;
    private final SmartDeckService smartDeckService;

    public HomeAction(DeckService deckService, StudyService studyService,
                      SmartDeckService smartDeckService) {
        this.deckService = deckService;
        this.studyService = studyService;
        this.smartDeckService = smartDeckService;
    }

    @Override
    public WebResponse handle(WebRequest req) {
        Map<String, Object> model = new HashMap<>();
        model.put("todayCount", studyService.todayCount());
        model.put("oftenWrongCount", smartDeckService.oftenWrongCount());
        model.put("staleCount", smartDeckService.staleCount());
        model.put("decks", deckService.deckSummaries());
        model.put("smartDecks", smartDeckService.smartDecks());
        model.put("directions", StudyDirection.values());
        model.put("message", req.flashed("message"));
        model.put("error", req.flashed("error"));
        return WebResponse.template("home", model);
    }
}
