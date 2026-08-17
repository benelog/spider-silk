package flashcard.web;

import spidersilk.App;
import spidersilk.WebRequest;
import spidersilk.WebResponse;

import flashcard.domain.SmartCondition;
import flashcard.service.SmartDeckService;

public class SmartDeckController implements Controller {

    private final SmartDeckService smartDeckService;

    public SmartDeckController(SmartDeckService smartDeckService) {
        this.smartDeckService = smartDeckService;
    }

    @Override
    public void register(App app) {
        app.post("/smart-decks", this::create);
        app.post("/smart-decks/{id}/delete", this::delete);
    }

    WebResponse create(WebRequest req) {
        smartDeckService.create(req.param("name"),
                req.paramEnum("condition", SmartCondition.class),
                req.param("param", ""));
        return WebResponse.redirect("/");
    }

    WebResponse delete(WebRequest req) {
        smartDeckService.delete(req.pathParamLong("id"));
        return WebResponse.redirect("/");
    }
}
