package flashcard.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import flashcard.domain.Card;

/**
 * Works with SQL directly through NamedParameterJdbcTemplate.
 * Instead of a framework generating SQL, the executed SQL is visible in the code.
 */
public class CardRepository {

    private static final String COLUMNS = "id, deck_id, text, meaning, created_at";
    private static final String CARD_COLUMNS =
            "card.id, card.deck_id, card.text, card.meaning, card.created_at";
    private static final RowMapper<Card> MAPPER = DataClassRowMapper.newInstance(Card.class);

    private final NamedParameterJdbcTemplate jdbc;

    public CardRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public Card insert(Card card) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                insert into card (deck_id, text, meaning, created_at)
                values (:deckId, :text, :meaning, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("deckId", card.deckId())
                        .addValue("text", card.text())
                        .addValue("meaning", card.meaning())
                        .addValue("createdAt", card.createdAt()),
                keyHolder, new String[] {"id"});
        return new Card(keyHolder.getKey().longValue(), card.deckId(),
                card.text(), card.meaning(), card.createdAt());
    }

    public void update(Card card) {
        jdbc.update("""
                update card
                set text = :text, meaning = :meaning
                where id = :id
                """,
                Map.of("id", card.id(), "text", card.text(), "meaning", card.meaning()));
    }

    public void deleteById(Long id) {
        jdbc.update("delete from card where id = :id", Map.of("id", id));
    }

    public Optional<Card> findById(Long id) {
        return jdbc.query("""
                select %s
                from card
                where id = :id
                """.formatted(COLUMNS), Map.of("id", id), MAPPER).stream().findFirst();
    }

    public List<Card> findByDeckId(Long deckId) {
        return jdbc.query("""
                select %s
                from card
                where deck_id = :deckId
                order by id
                """.formatted(COLUMNS), Map.of("deckId", deckId), MAPPER);
    }

    /** Today's review queue: cards whose due date has passed. */
    public List<Card> findDue(LocalDate today) {
        return jdbc.query("""
                select %s
                from card
                    join review_state on review_state.card_id = card.id
                where review_state.due_date <= :today
                order by review_state.due_date, card.id
                """.formatted(CARD_COLUMNS), Map.of("today", today), MAPPER);
    }

    public long countDue(LocalDate today) {
        return jdbc.queryForObject("""
                select count(*)
                from review_state
                where due_date <= :today
                """, Map.of("today", today), Long.class);
    }

    /** Often missed cards: based on attempt count and wrong-answer rate. */
    public List<Card> findOftenWrong(int minAttempts, int minWrongPercent) {
        return jdbc.query("""
                select %s
                from card
                    join review_state on review_state.card_id = card.id
                where review_state.correct_count + review_state.wrong_count >= :minAttempts
                  and review_state.wrong_count * 100 >=
                      (review_state.correct_count + review_state.wrong_count) * :minWrongPercent
                order by review_state.wrong_count desc, card.id
                """.formatted(CARD_COLUMNS),
                Map.of("minAttempts", minAttempts, "minWrongPercent", minWrongPercent), MAPPER);
    }

    public long countOftenWrong(int minAttempts, int minWrongPercent) {
        return jdbc.queryForObject("""
                select count(*)
                from review_state
                where correct_count + wrong_count >= :minAttempts
                  and wrong_count * 100 >= (correct_count + wrong_count) * :minWrongPercent
                """,
                Map.of("minAttempts", minAttempts, "minWrongPercent", minWrongPercent),
                Long.class);
    }

    /** Stale cards: last review (or creation) time is before the threshold. */
    public List<Card> findStale(LocalDateTime threshold) {
        return jdbc.query("""
                select %s
                from card
                    left join review_state on review_state.card_id = card.id
                where coalesce(review_state.last_reviewed_at, card.created_at) < :threshold
                order by review_state.last_reviewed_at nulls first, card.id
                """.formatted(CARD_COLUMNS), Map.of("threshold", threshold), MAPPER);
    }

    public long countStale(LocalDateTime threshold) {
        return jdbc.queryForObject("""
                select count(*)
                from card
                    left join review_state on review_state.card_id = card.id
                where coalesce(review_state.last_reviewed_at, card.created_at) < :threshold
                """, Map.of("threshold", threshold), Long.class);
    }

    /** Recently added cards. */
    public List<Card> findRecent(LocalDateTime threshold) {
        return jdbc.query("""
                select %s
                from card
                where created_at >= :threshold
                order by created_at desc, id desc
                """.formatted(COLUMNS), Map.of("threshold", threshold), MAPPER);
    }

    /** Cards with a specific tag. */
    public List<Card> findByTagName(String tagName) {
        return jdbc.query("""
                select %s
                from card
                    join card_tag on card_tag.card_id = card.id
                    join tag on tag.id = card_tag.tag_id
                where tag.name = :tagName
                order by card.id
                """.formatted(CARD_COLUMNS), Map.of("tagName", tagName), MAPPER);
    }
}
