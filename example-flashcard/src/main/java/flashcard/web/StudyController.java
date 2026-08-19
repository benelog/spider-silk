package flashcard.web;

import java.util.HashMap;
import java.util.Map;

import spidersilk.WebRequest;
import spidersilk.WebResponse;

import flashcard.domain.Card;
import flashcard.domain.SmartCondition;
import flashcard.service.SmartDeckService;
import flashcard.service.StudyDirection;
import flashcard.service.StudySession;
import flashcard.service.StudyService;

public class StudyController {

    private static final String SESSION_KEY = "studySession";

    private final StudyService studyService;
    private final SmartDeckService smartDeckService;

    public StudyController(StudyService studyService, SmartDeckService smartDeckService) {
        this.studyService = studyService;
        this.smartDeckService = smartDeckService;
    }


    public WebResponse startDeckStudy(WebRequest req) {
        StudyDirection direction = req.paramEnum("direction", StudyDirection.class);
        req.sessionAttr(SESSION_KEY,
                studyService.startDeckSession(req.pathParamLong("deckId"), direction));
        return WebResponse.redirect("/study");
    }

    public WebResponse startTodayStudy(WebRequest req) {
        StudyDirection direction = req.paramEnum("direction", StudyDirection.class);
        req.sessionAttr(SESSION_KEY, studyService.startTodaySession(direction));
        return WebResponse.redirect("/study");
    }

    public WebResponse startSmartStudy(WebRequest req) {
        StudyDirection direction = req.paramEnum("direction", StudyDirection.class);
        req.sessionAttr(SESSION_KEY, studyService.startSmartSession(
                smartDeckService.getSmartDeck(req.pathParamLong("smartDeckId")), direction));
        return WebResponse.redirect("/study");
    }

    public WebResponse startPresetStudy(WebRequest req) {
        StudyDirection direction = req.paramEnum("direction", StudyDirection.class);
        SmartCondition condition = req.pathParamEnum("condition", SmartCondition.class);
        req.sessionAttr(SESSION_KEY, studyService.startPresetSession(condition, direction));
        return WebResponse.redirect("/study");
    }

    public WebResponse showStudy(WebRequest req) {
        StudySession studySession = current(req);
        if (studySession == null || studySession.isEmpty()) {
            return WebResponse.redirect("/");
        }
        Map<String, Object> model = new HashMap<>();
        model.put("study", studySession);

        if (studySession.isRoundFinished()) {
            return WebResponse.template(studySession.hasWrongCards()
                    ? "study-round-end.jte" : "study-done.jte", model);
        }

        Card card = studyService.currentCard(studySession);
        boolean textFirst = studySession.getDirection() == StudyDirection.TEXT_TO_MEANING;
        model.put("question", textFirst ? card.text() : card.meaning());
        model.put("answer", textFirst ? card.meaning() : card.text());
        model.put("flipped", req.paramBoolean("flipped", false));
        return WebResponse.template("study.jte", model);
    }

    public WebResponse answer(WebRequest req) {
        StudySession studySession = current(req);
        if (studySession == null) {
            return WebResponse.redirect("/");
        }
        studyService.answer(studySession, req.paramBoolean("correct", false));
        return WebResponse.redirect("/study");
    }

    public WebResponse retry(WebRequest req) {
        StudySession studySession = current(req);
        if (studySession != null) {
            studySession.startRetryRound();
        }
        return WebResponse.redirect("/study");
    }

    public WebResponse finish(WebRequest req) {
        req.removeSessionAttr(SESSION_KEY);
        return WebResponse.redirect("/");
    }

    private StudySession current(WebRequest req) {
        return req.sessionAttr(SESSION_KEY);
    }
}
