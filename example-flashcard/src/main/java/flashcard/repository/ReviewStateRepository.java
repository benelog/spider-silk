package flashcard.repository;

import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import flashcard.domain.ReviewState;

public class ReviewStateRepository {

    private static final RowMapper<ReviewState> MAPPER =
            DataClassRowMapper.newInstance(ReviewState.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public ReviewStateRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.insert = new SimpleJdbcInsert(dataSource)
                .withTableName("review_state")
                .usingGeneratedKeyColumns("id");
    }

    public void insert(ReviewState state) {
        insert.execute(params(state));
    }

    public void update(ReviewState state) {
        jdbc.update("""
                update review_state
                set interval_days    = :intervalDays,
                    due_date         = :dueDate,
                    correct_count    = :correctCount,
                    wrong_count      = :wrongCount,
                    last_reviewed_at = :lastReviewedAt
                where id = :id
                """, params(state).addValue("id", state.id()));
    }

    public Optional<ReviewState> findByCardId(Long cardId) {
        return jdbc.query("""
                select id, card_id, interval_days, due_date, correct_count, wrong_count,
                       last_reviewed_at
                from review_state
                where card_id = :cardId
                """, Map.of("cardId", cardId), MAPPER).stream().findFirst();
    }

    private MapSqlParameterSource params(ReviewState state) {
        return new MapSqlParameterSource()
                .addValue("cardId", state.cardId())
                .addValue("intervalDays", state.intervalDays())
                .addValue("dueDate", state.dueDate())
                .addValue("correctCount", state.correctCount())
                .addValue("wrongCount", state.wrongCount())
                .addValue("lastReviewedAt", state.lastReviewedAt());
    }
}
