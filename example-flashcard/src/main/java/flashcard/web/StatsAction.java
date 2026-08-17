package flashcard.web;

import java.util.Map;

import spidersilk.Handler;
import spidersilk.WebRequest;
import spidersilk.WebResponse;

import flashcard.service.StatsService;

/** One route, so the class is the handler. See {@link HomeAction}. */
public class StatsAction implements Handler {

    private final StatsService statsService;

    public StatsAction(StatsService statsService) {
        this.statsService = statsService;
    }

    @Override
    public WebResponse handle(WebRequest req) {
        return WebResponse.render("stats.jte", Map.of("stats", statsService.overview()));
    }
}
