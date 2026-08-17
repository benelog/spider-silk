package flashcard.domain;

import java.time.LocalDate;

/** One day's study volume: correct and wrong answer counts. */
public record DailyStat(LocalDate studyDate, long correctCount, long wrongCount) {

    public long total() {
        return correctCount + wrongCount;
    }
}
