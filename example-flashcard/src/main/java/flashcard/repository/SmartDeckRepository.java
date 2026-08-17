package flashcard.repository;

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

import flashcard.domain.SmartDeck;

public class SmartDeckRepository {

    private static final RowMapper<SmartDeck> MAPPER =
            DataClassRowMapper.newInstance(SmartDeck.class);

    private final NamedParameterJdbcTemplate jdbc;

    public SmartDeckRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public SmartDeck insert(SmartDeck smartDeck) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                insert into smart_deck (name, condition_type, param)
                values (:name, :conditionType, :param)
                """,
                new MapSqlParameterSource()
                        .addValue("name", smartDeck.name())
                        .addValue("conditionType", smartDeck.conditionType().name())
                        .addValue("param", smartDeck.param()),
                keyHolder, new String[] {"id"});
        return new SmartDeck(keyHolder.getKey().longValue(), smartDeck.name(),
                smartDeck.conditionType(), smartDeck.param());
    }

    public void deleteById(Long id) {
        jdbc.update("delete from smart_deck where id = :id", Map.of("id", id));
    }

    public Optional<SmartDeck> findById(Long id) {
        return jdbc.query("""
                select id, name, condition_type, param
                from smart_deck
                where id = :id
                """, Map.of("id", id), MAPPER).stream().findFirst();
    }

    public List<SmartDeck> findAllOrdered() {
        return jdbc.query("""
                select id, name, condition_type, param
                from smart_deck
                order by id
                """, Map.of(), MAPPER);
    }
}
