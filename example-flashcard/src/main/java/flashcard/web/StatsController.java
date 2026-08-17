package flashcard.web;

import java.util.Map;

import spidersilk.App;
import spidersilk.WebContext;

import flashcard.service.StatsService;

public class StatsController implements Controller {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @Override
    public void register(App app) {
        app.get("/stats", this::stats);
    }

    void stats(WebContext ctx) {
        ctx.render("stats.jte", Map.of("stats", statsService.overview()));
    }
}
