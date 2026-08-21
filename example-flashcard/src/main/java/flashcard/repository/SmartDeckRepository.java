package flashcard.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import flashcard.domain.SmartCondition;
import flashcard.domain.SmartDeck;

public class SmartDeckRepository {

    private static final RowMapper<SmartDeck> MAPPER = (rs, rowNum) -> new SmartDeck(
            rs.getObject("id", Long.class),
            rs.getString("name"),
            SmartCondition.valueOf(rs.getString("condition_type")),
            rs.getString("param"));

    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public SmartDeckRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.insert = new SimpleJdbcInsert(dataSource)
                .withTableName("smart_deck")
                .usingGeneratedKeyColumns("id");
    }

    public SmartDeck insert(SmartDeck smartDeck) {
        Long id = insert.executeAndReturnKey(
                new MapSqlParameterSource()
                        .addValue("name", smartDeck.name())
                        .addValue("condition_type", smartDeck.conditionType().name())
                        .addValue("param", smartDeck.param())).longValue();
        return new SmartDeck(id, smartDeck.name(),
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
