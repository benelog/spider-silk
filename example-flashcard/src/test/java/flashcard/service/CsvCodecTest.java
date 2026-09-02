package flashcard.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import flashcard.domain.Card;
import flashcard.domain.CardWithTags;
import flashcard.service.CsvCodec.CsvCard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvCodecTest {

    @Test
    void parsesTheBasicFormat() {
        List<CsvCard> cards = CsvCodec.parse("""
                apple,a round fruit,fruit;basic
                run,move fast
                """);

        assertThat(cards).hasSize(2);
        assertThat(cards.get(0))
                .isEqualTo(new CsvCard("apple", "a round fruit", List.of("fruit", "basic")));
        assertThat(cards.get(1)).isEqualTo(new CsvCard("run", "move fast", List.of()));
    }

    @Test
    void doubleQuotedValuesMayContainCommas() {
        List<CsvCard> cards = CsvCodec.parse("\"a, b\",\"say \"\"hi\"\"\",tag");

        assertThat(cards.get(0).text()).isEqualTo("a, b");
        assertThat(cards.get(0).meaning()).isEqualTo("say \"hi\"");
    }

    @Test
    void skipsBlankLines() {
        List<CsvCard> cards = CsvCodec.parse("a,1\n\n\nb,2\n");
        assertThat(cards).hasSize(2);
    }

    @Test
    void throwsWithLineNumberWhenColumnsAreMissing() {
        assertThatThrownBy(() -> CsvCodec.parse("a,1\nbroken\n"))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageContaining("Line 2");
    }

    @Test
    void exportedFormatCanBeParsedBack() {
        Card card = Card.create(1L, "a, b", "meaning", LocalDateTime.now(ZoneId.systemDefault()));
        String csv = CsvCodec.format(List.of(new CardWithTags(card, List.of("tag1", "tag2"))));

        List<CsvCard> parsed = CsvCodec.parse(csv);
        assertThat(parsed.get(0).text()).isEqualTo("a, b");
        assertThat(parsed.get(0).tags()).isEqualTo(List.of("tag1", "tag2"));
    }
}
