package flashcard.repository;

import java.time.LocalDate;
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

import flashcard.domain.Deck;
import flashcard.domain.DeckStat;
import flashcard.domain.DeckSummary;

public class DeckRepository {

    private static final RowMapper<Deck> MAPPER = DataClassRowMapper.newInstance(Deck.class);

    private final NamedParameterJdbcTemplate jdbc;

    public DeckRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public Deck insert(Deck deck) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                insert into deck (name, created_at)
                values (:name, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("name", deck.name())
                        .addValue("createdAt", deck.createdAt()),
                keyHolder, new String[] {"id"});
        return new Deck(keyHolder.getKey().longValue(), deck.name(), deck.createdAt());
    }

    public void update(Deck deck) {
        jdbc.update("update deck set name = :name where id = :id",
                Map.of("id", deck.id(), "name", deck.name()));
    }

    public void deleteById(Long id) {
        jdbc.update("delete from deck where id = :id", Map.of("id", id));
    }

    public Optional<Deck> findById(Long id) {
        return jdbc.query("""
                select id, name, created_at
                from deck
                where id = :id
                """, Map.of("id", id), MAPPER).stream().findFirst();
    }

    /** Counts each deck's cards and today's due cards in one query. */
    public List<DeckSummary> findAllSummaries(LocalDate today) {
        return jdbc.query("""
                select deck.id,
                       deck.name,
                       count(distinct card.id) as card_count,
                       count(distinct case
                                          when review_state.due_date <= :today then card.id
                           end)                as due_count
                from deck
                    left join card on card.deck_id = deck.id
                    left join review_state on review_state.card_id = card.id
                group by deck.id, deck.name
                order by deck.id
                """, Map.of("today", today), DataClassRowMapper.newInstance(DeckSummary.class));
    }

    /** Per-deck performance: grading count and correct count. */
    public List<DeckStat> findAllStats() {
        return jdbc.query("""
                select deck.id                                            as deck_id,
                       deck.name                                          as deck_name,
                       count(review_log.id)                               as total_count,
                       coalesce(sum(case when review_log.correct then 1 else 0 end), 0)
                                                                          as correct_count
                from deck
                    left join card on card.deck_id = deck.id
                    left join review_log on review_log.card_id = card.id
                group by deck.id, deck.name
                order by deck.id
                """, Map.of(), DataClassRowMapper.newInstance(DeckStat.class));
    }
}
