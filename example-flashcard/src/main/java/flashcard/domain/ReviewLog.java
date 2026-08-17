package flashcard.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One row per grading. The source data for statistics. */
public record ReviewLog(Long id, Long cardId, boolean correct, boolean retryRound,
                        LocalDateTime reviewedAt, LocalDate studyDate) {

    public static ReviewLog of(Long cardId, boolean correct, boolean retryRound,
                               LocalDateTime now) {
        return new ReviewLog(null, cardId, correct, retryRound, now, now.toLocalDate());
    }
}
