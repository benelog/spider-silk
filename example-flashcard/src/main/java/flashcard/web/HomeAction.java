package flashcard.web;

import net.benelog.spidersilk.Handler;
import net.benelog.spidersilk.Model;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;

import flashcard.service.DeckService;
import flashcard.service.SmartDeckService;
import flashcard.service.StudyDirection;
import flashcard.service.StudyService;

/**
 * One route, so the class is the handler: it implements {@link Handler} and is
 * registered as itself, {@code app.get("/", homeAction)}.
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
        return WebResponse.template("home", Model.of(
                "todayCount", studyService.todayCount(),
                "oftenWrongCount", smartDeckService.oftenWrongCount(),
                "staleCount", smartDeckService.staleCount(),
                "decks", deckService.deckSummaries(),
                "smartDecks", smartDeckService.smartDecks(),
                "directions", StudyDirection.values(),
                "message", req.flashed("message"),
                "error", req.flashed("error")));
    }
}
