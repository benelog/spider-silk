package flashcard.web;

import spidersilk.App;
import spidersilk.WebContext;

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

    void create(WebContext ctx) {
        smartDeckService.create(ctx.param("name"),
                ctx.paramEnum("condition", SmartCondition.class),
                ctx.param("param", ""));
        ctx.redirect("/");
    }

    void delete(WebContext ctx) {
        smartDeckService.delete(ctx.pathParamLong("id"));
        ctx.redirect("/");
    }
}
