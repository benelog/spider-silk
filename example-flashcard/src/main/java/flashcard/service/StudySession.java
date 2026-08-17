package flashcard.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * A study session in progress. Serializable because it lives in the HTTP session.
 * After a full pass, retry rounds repeat with only the missed cards.
 */
public class StudySession implements Serializable {

    private final String title;
    private final StudyDirection direction;
    private List<Long> cardIds;
    private List<Long> wrongCardIds = new ArrayList<>();
    private int index;
    private int round = 1;

    public StudySession(String title, StudyDirection direction, List<Long> cardIds) {
        this.title = title;
        this.direction = direction;
        this.cardIds = new ArrayList<>(cardIds);
    }

    public Long currentCardId() {
        return cardIds.get(index);
    }

    public void answer(boolean correct) {
        if (!correct) {
            wrongCardIds.add(currentCardId());
        }
        index++;
    }

    public boolean isRoundFinished() {
        return index >= cardIds.size();
    }

    public boolean hasWrongCards() {
        return !wrongCardIds.isEmpty();
    }

    /** Starts the next round with only the missed cards. */
    public void startRetryRound() {
        cardIds = wrongCardIds;
        wrongCardIds = new ArrayList<>();
        index = 0;
        round++;
    }

    /** Gradings in retry rounds do not affect the review schedule. */
    public boolean isRetryRound() {
        return round > 1;
    }

    public String getTitle() {
        return title;
    }

    public StudyDirection getDirection() {
        return direction;
    }

    public int getRound() {
        return round;
    }

    public int getPosition() {
        return Math.min(index + 1, cardIds.size());
    }

    public int getTotal() {
        return cardIds.size();
    }

    public int getWrongCount() {
        return wrongCardIds.size();
    }

    public boolean isEmpty() {
        return cardIds.isEmpty();
    }
}
