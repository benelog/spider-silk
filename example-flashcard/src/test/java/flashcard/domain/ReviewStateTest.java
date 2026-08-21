package flashcard.domain;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class ReviewStateTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 8, 17, 10, 0);

    @Test
    void initialStateHasNoReviewHistory() {
        ReviewState state = ReviewState.initial(1L);
        assertThat(state.intervalDays()).isEqualTo(0);
        assertThat(state.dueDate()).isNull();
        assertThat(state.totalCount()).isEqualTo(0);
    }

    @Test
    void correctAnswersGrowTheIntervalOneSixThenTwoPointFiveTimes() {
        ReviewState state = ReviewState.initial(1L);

        state = state.reviewed(true, now);
        assertThat(state.intervalDays()).isEqualTo(1);
        assertThat(state.dueDate()).isEqualTo(now.toLocalDate().plusDays(1));

        state = state.reviewed(true, now);
        assertThat(state.intervalDays()).isEqualTo(6);

        state = state.reviewed(true, now);
        assertThat(state.intervalDays()).isEqualTo(15);

        state = state.reviewed(true, now);
        assertThat(state.intervalDays()).isEqualTo(38);

        assertThat(state.correctCount()).isEqualTo(4);
        assertThat(state.wrongCount()).isEqualTo(0);
    }

    @Test
    void wrongAnswerResetsTheIntervalToOneDay() {
        ReviewState state = ReviewState.initial(1L)
                .reviewed(true, now)
                .reviewed(true, now)
                .reviewed(false, now);

        assertThat(state.intervalDays()).isEqualTo(1);
        assertThat(state.dueDate()).isEqualTo(now.toLocalDate().plusDays(1));
        assertThat(state.correctCount()).isEqualTo(2);
        assertThat(state.wrongCount()).isEqualTo(1);
        assertThat(state.lastReviewedAt()).isEqualTo(now);
    }
}
