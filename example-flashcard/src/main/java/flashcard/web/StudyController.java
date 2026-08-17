package flashcard.web;

import java.util.HashMap;
import java.util.Map;

import steelspider.App;
import steelspider.WebContext;

import flashcard.domain.Card;
import flashcard.domain.SmartCondition;
import flashcard.service.SmartDeckService;
import flashcard.service.StudyDirection;
import flashcard.service.StudySession;
import flashcard.service.StudyService;

public class StudyController implements Controller {

    private static final String SESSION_KEY = "studySession";

    private final StudyService studyService;
    private final SmartDeckService smartDeckService;

    public StudyController(StudyService studyService, SmartDeckService smartDeckService) {
        this.studyService = studyService;
        this.smartDeckService = smartDeckService;
    }

    @Override
    public void register(App app) {
        app.post("/study/deck/{deckId}", this::startDeckStudy);
        app.post("/study/today", this::startTodayStudy);
        app.post("/study/smart/{smartDeckId}", this::startSmartStudy);
        app.post("/study/preset/{condition}", this::startPresetStudy);
        app.get("/study", this::showStudy);
        app.post("/study/answer", this::answer);
        app.post("/study/retry", this::retry);
        app.post("/study/finish", this::finish);
    }

    void startDeckStudy(WebContext ctx) {
        StudyDirection direction = ctx.paramEnum("direction", StudyDirection.class);
        ctx.sessionAttr(SESSION_KEY,
                studyService.startDeckSession(ctx.pathParamLong("deckId"), direction));
        ctx.redirect("/study");
    }

    void startTodayStudy(WebContext ctx) {
        StudyDirection direction = ctx.paramEnum("direction", StudyDirection.class);
        ctx.sessionAttr(SESSION_KEY, studyService.startTodaySession(direction));
        ctx.redirect("/study");
    }

    void startSmartStudy(WebContext ctx) {
        StudyDirection direction = ctx.paramEnum("direction", StudyDirection.class);
        ctx.sessionAttr(SESSION_KEY, studyService.startSmartSession(
                smartDeckService.getSmartDeck(ctx.pathParamLong("smartDeckId")), direction));
        ctx.redirect("/study");
    }

    void startPresetStudy(WebContext ctx) {
        StudyDirection direction = ctx.paramEnum("direction", StudyDirection.class);
        SmartCondition condition = ctx.pathParamEnum("condition", SmartCondition.class);
        ctx.sessionAttr(SESSION_KEY, studyService.startPresetSession(condition, direction));
        ctx.redirect("/study");
    }

    void showStudy(WebContext ctx) {
        StudySession studySession = current(ctx);
        if (studySession == null || studySession.isEmpty()) {
            ctx.redirect("/");
            return;
        }
        Map<String, Object> model = new HashMap<>();
        model.put("study", studySession);

        if (studySession.isRoundFinished()) {
            ctx.render(studySession.hasWrongCards()
                    ? "study-round-end.jte" : "study-done.jte", model);
            return;
        }

        Card card = studyService.currentCard(studySession);
        boolean textFirst = studySession.getDirection() == StudyDirection.TEXT_TO_MEANING;
        model.put("question", textFirst ? card.text() : card.meaning());
        model.put("answer", textFirst ? card.meaning() : card.text());
        model.put("flipped", ctx.paramBoolean("flipped", false));
        ctx.render("study.jte", model);
    }

    void answer(WebContext ctx) {
        StudySession studySession = current(ctx);
        if (studySession == null) {
            ctx.redirect("/");
            return;
        }
        studyService.answer(studySession, ctx.paramBoolean("correct", false));
        ctx.redirect("/study");
    }

    void retry(WebContext ctx) {
        StudySession studySession = current(ctx);
        if (studySession != null) {
            studySession.startRetryRound();
        }
        ctx.redirect("/study");
    }

    void finish(WebContext ctx) {
        ctx.removeSessionAttr(SESSION_KEY);
        ctx.redirect("/");
    }

    private StudySession current(WebContext ctx) {
        return ctx.sessionAttr(SESSION_KEY);
    }
}
