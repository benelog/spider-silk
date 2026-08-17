package flashcard.service;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import flashcard.domain.Card;
import flashcard.domain.CardWithTags;
import flashcard.service.CsvCodec.CsvCard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvCodecTest {

    @Test
    void parsesTheBasicFormat() {
        List<CsvCard> cards = CsvCodec.parse("""
                apple,a round fruit,fruit;basic
                run,move fast
                """);

        assertEquals(2, cards.size());
        assertEquals(new CsvCard("apple", "a round fruit", List.of("fruit", "basic")),
                cards.get(0));
        assertEquals(new CsvCard("run", "move fast", List.of()), cards.get(1));
    }

    @Test
    void doubleQuotedValuesMayContainCommas() {
        List<CsvCard> cards = CsvCodec.parse("\"a, b\",\"say \"\"hi\"\"\",tag");

        assertEquals("a, b", cards.get(0).text());
        assertEquals("say \"hi\"", cards.get(0).meaning());
    }

    @Test
    void skipsBlankLines() {
        List<CsvCard> cards = CsvCodec.parse("a,1\n\n\nb,2\n");
        assertEquals(2, cards.size());
    }

    @Test
    void throwsWithLineNumberWhenColumnsAreMissing() {
        CsvFormatException e = assertThrows(CsvFormatException.class,
                () -> CsvCodec.parse("a,1\nbroken\n"));
        assertTrue(e.getMessage().contains("Line 2"));
    }

    @Test
    void exportedFormatCanBeParsedBack() {
        Card card = Card.create(1L, "a, b", "meaning", LocalDateTime.now());
        String csv = CsvCodec.format(List.of(new CardWithTags(card, List.of("tag1", "tag2"))));

        List<CsvCard> parsed = CsvCodec.parse(csv);
        assertEquals("a, b", parsed.get(0).text());
        assertEquals(List.of("tag1", "tag2"), parsed.get(0).tags());
    }
}
