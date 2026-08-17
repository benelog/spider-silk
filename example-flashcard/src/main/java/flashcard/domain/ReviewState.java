package flashcard.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The review schedule and cumulative score of a single card.
 * The core spaced-repetition logic lives inside this record.
 */
public record ReviewState(Long id, Long cardId, int intervalDays, LocalDate dueDate,
                          int correctCount, int wrongCount, LocalDateTime lastReviewedAt) {

    static final int FIRST_INTERVAL = 1;
    static final int SECOND_INTERVAL = 6;
    static final double MULTIPLIER = 2.5;

    public static ReviewState initial(Long cardId) {
        return new ReviewState(null, cardId, 0, null, 0, 0, null);
    }

    /**
     * On a correct answer the interval grows 1 day -> 6 days -> 2.5x each time.
     * On a wrong answer it falls back to 1 day.
     */
    public ReviewState reviewed(boolean correct, LocalDateTime now) {
        int nextInterval = correct ? nextIntervalOnCorrect() : FIRST_INTERVAL;
        return new ReviewState(
                id, cardId,
                nextInterval,
                now.toLocalDate().plusDays(nextInterval),
                correctCount + (correct ? 1 : 0),
                wrongCount + (correct ? 0 : 1),
                now
        );
    }

    private int nextIntervalOnCorrect() {
        if (intervalDays == 0) {
            return FIRST_INTERVAL;
        }
        if (intervalDays == FIRST_INTERVAL) {
            return SECOND_INTERVAL;
        }
        return (int) Math.round(intervalDays * MULTIPLIER);
    }

    public int totalCount() {
        return correctCount + wrongCount;
    }
}
