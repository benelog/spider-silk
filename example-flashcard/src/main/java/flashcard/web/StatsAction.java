package flashcard.web;

import java.util.Map;

import net.benelog.spidersilk.Handler;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;

import flashcard.service.StatsService;

/** One route, so the class is the handler. See {@link HomeAction}. */
public class StatsAction implements Handler {

    private final StatsService statsService;

    public StatsAction(StatsService statsService) {
        this.statsService = statsService;
    }

    @Override
    public WebResponse handle(WebRequest req) {
        Map<String, Object> stats = Map.of("stats", statsService.overview());
        return WebResponse.template("stats", stats);
    }
}
