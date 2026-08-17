package flashcard.web;

import spidersilk.WebRequest;
import spidersilk.WebResponse;

import flashcard.domain.SmartCondition;
import flashcard.service.SmartDeckService;

public class SmartDeckController {

    private final SmartDeckService smartDeckService;

    public SmartDeckController(SmartDeckService smartDeckService) {
        this.smartDeckService = smartDeckService;
    }


    public WebResponse create(WebRequest req) {
        smartDeckService.create(req.param("name"),
                req.paramEnum("condition", SmartCondition.class),
                req.param("param", ""));
        return WebResponse.redirect("/");
    }

    public WebResponse delete(WebRequest req) {
        smartDeckService.delete(req.pathParamLong("id"));
        return WebResponse.redirect("/");
    }
}
