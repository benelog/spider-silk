package flashcard.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import flashcard.domain.CardTagName;
import flashcard.domain.Tag;

public class TagRepository {

    private static final RowMapper<Tag> MAPPER = DataClassRowMapper.newInstance(Tag.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public TagRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.insert = new SimpleJdbcInsert(dataSource)
                .withTableName("tag")
                .usingGeneratedKeyColumns("id");
    }

    public Tag insert(Tag tag) {
        Long id = insert.executeAndReturnKey(
                new MapSqlParameterSource().addValue("name", tag.name())).longValue();
        return new Tag(id, tag.name());
    }

    public Optional<Tag> findByName(String name) {
        return jdbc.query("""
                select id, name
                from tag
                where name = :name
                """, Map.of("name", name), MAPPER).stream().findFirst();
    }

    /** Loads the tags of every card in a deck as (card id, tag name) pairs at once. */
    public List<CardTagName> findTagNamesByDeckId(Long deckId) {
        return jdbc.query("""
                select card_tag.card_id, tag.name as tag_name
                from card_tag
                    join card on card.id = card_tag.card_id
                    join tag on tag.id = card_tag.tag_id
                where card.deck_id = :deckId
                order by tag.name
                """, Map.of("deckId", deckId), DataClassRowMapper.newInstance(CardTagName.class));
    }

    public List<String> findTagNamesByCardId(Long cardId) {
        return jdbc.queryForList("""
                select tag.name
                from card_tag
                    join tag on tag.id = card_tag.tag_id
                where card_tag.card_id = :cardId
                order by tag.name
                """, Map.of("cardId", cardId), String.class);
    }

    public List<String> findAllNames() {
        return jdbc.queryForList("""
                select name
                from tag
                order by name
                """, Map.of(), String.class);
    }

    /** An upsert that keeps an existing link untouched. Uses H2's MERGE syntax. */
    public void attach(Long cardId, Long tagId) {
        jdbc.update("""
                merge into card_tag (card_id, tag_id)
                key (card_id, tag_id)
                values (:cardId, :tagId)
                """, Map.of("cardId", cardId, "tagId", tagId));
    }

    public void detachAll(Long cardId) {
        jdbc.update("delete from card_tag where card_id = :cardId", Map.of("cardId", cardId));
    }
}
