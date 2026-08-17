package flashcard.web;

import java.util.Map;

import spidersilk.App;
import spidersilk.WebRequest;
import spidersilk.WebResponse;

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

    WebResponse stats(WebRequest req) {
        return WebResponse.render("stats.jte", Map.of("stats", statsService.overview()));
    }
}
