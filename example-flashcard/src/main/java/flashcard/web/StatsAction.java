package flashcard.web;

import net.benelog.spidersilk.Handler;
import net.benelog.spidersilk.Model;
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
        return WebResponse.template("stats", Model.of("stats", statsService.overview()));
    }
}
