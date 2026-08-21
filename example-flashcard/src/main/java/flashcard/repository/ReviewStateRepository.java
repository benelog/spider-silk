package flashcard.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import flashcard.domain.ReviewState;

public class ReviewStateRepository {

    private static final RowMapper<ReviewState> MAPPER = (rs, rowNum) -> new ReviewState(
            rs.getObject("id", Long.class),
            rs.getObject("card_id", Long.class),
            rs.getInt("interval_days"),
            rs.getObject("due_date", LocalDate.class),
            rs.getInt("correct_count"),
            rs.getInt("wrong_count"),
            rs.getObject("last_reviewed_at", LocalDateTime.class));

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
                set interval_days    = :interval_days,
                    due_date         = :due_date,
                    correct_count    = :correct_count,
                    wrong_count      = :wrong_count,
                    last_reviewed_at = :last_reviewed_at
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

    private static MapSqlParameterSource params(ReviewState state) {
        return new MapSqlParameterSource()
                .addValue("card_id", state.cardId())
                .addValue("interval_days", state.intervalDays())
                .addValue("due_date", state.dueDate())
                .addValue("correct_count", state.correctCount())
                .addValue("wrong_count", state.wrongCount())
                .addValue("last_reviewed_at", state.lastReviewedAt());
    }
}
