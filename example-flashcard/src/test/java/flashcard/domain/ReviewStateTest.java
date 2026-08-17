package flashcard.domain;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReviewStateTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 8, 17, 10, 0);

    @Test
    void initialStateHasNoReviewHistory() {
        ReviewState state = ReviewState.initial(1L);
        assertEquals(0, state.intervalDays());
        assertNull(state.dueDate());
        assertEquals(0, state.totalCount());
    }

    @Test
    void correctAnswersGrowTheIntervalOneSixThenTwoPointFiveTimes() {
        ReviewState state = ReviewState.initial(1L);

        state = state.reviewed(true, now);
        assertEquals(1, state.intervalDays());
        assertEquals(now.toLocalDate().plusDays(1), state.dueDate());

        state = state.reviewed(true, now);
        assertEquals(6, state.intervalDays());

        state = state.reviewed(true, now);
        assertEquals(15, state.intervalDays());

        state = state.reviewed(true, now);
        assertEquals(38, state.intervalDays());

        assertEquals(4, state.correctCount());
        assertEquals(0, state.wrongCount());
    }

    @Test
    void wrongAnswerResetsTheIntervalToOneDay() {
        ReviewState state = ReviewState.initial(1L)
                .reviewed(true, now)
                .reviewed(true, now)
                .reviewed(false, now);

        assertEquals(1, state.intervalDays());
        assertEquals(now.toLocalDate().plusDays(1), state.dueDate());
        assertEquals(2, state.correctCount());
        assertEquals(1, state.wrongCount());
        assertEquals(now, state.lastReviewedAt());
    }
}
