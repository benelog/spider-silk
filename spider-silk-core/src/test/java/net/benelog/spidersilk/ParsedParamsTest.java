package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestRequest;

/** The parser seam: one lambda covers the types that have no named form. */
class ParsedParamsTest {

    @Test
    void aParserCoversTheTypesWithNoNamedForm() {
        WebRequest request = TestRequest.get("/cards")
                .queryParam("since", "2026-03-01")
                .queryParam("owner", "5a1f3c2e-0000-4000-8000-000000000001")
                .queryParam("page", "3")
                .build();

        LocalDate since = request.param("since", LocalDate::parse);
        UUID owner = request.param("owner", UUID::fromString);
        int page = request.param("page", Integer::parseInt, 1);

        assertThat(since).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(owner).isEqualTo(UUID.fromString("5a1f3c2e-0000-4000-8000-000000000001"));
        assertThat(page).isEqualTo(3);
    }

    /** {@code LocalDate.parse} throws a DateTimeParseException, which is not an IllegalArgumentException. */
    @Test
    void aDateThatDoesNotExistIsA400RatherThanA500() {
        WebRequest request = TestRequest.get("/cards").queryParam("since", "2026-13-01").build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.param("since", LocalDate::parse))
                .satisfies(e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("since").contains("2026-13-01");
                });
    }

    @Test
    void aValueTheParserRejectsIsA400NamingTheParameter() {
        WebRequest request = TestRequest.get("/cards").queryParam("owner", "not-a-uuid").build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.param("owner", UUID::fromString))
                .satisfies(e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("owner").contains("not-a-uuid");
                });
    }

    @Test
    void aMissingParameterIsA400WithoutTheDefaultForm() {
        WebRequest request = TestRequest.get("/cards").build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.param("since", LocalDate::parse))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void theDefaultAnswersForAnAbsentParameter() {
        WebRequest request = TestRequest.get("/cards").build();

        int page = request.param("page", Integer::parseInt, 1);
        LocalDate since = request.param("since", LocalDate::parse, LocalDate.EPOCH);

        assertThat(page).isEqualTo(1);
        assertThat(since).isEqualTo(LocalDate.EPOCH);
    }

    /** The default covers absence only, the same contract as paramLong(name, default). */
    @Test
    void theDefaultDoesNotCoverAValueTheParserRejects() {
        WebRequest request = TestRequest.get("/cards").queryParam("page", "x").build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.param("page", Integer::parseInt, 1))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void aPathVariableTakesAParserToo() {
        WebRequest request = TestRequest.get("/decks/7")
                .pathParam("deckId", "7")
                .build();

        int deckId = request.pathParam("deckId", Integer::parseInt);

        assertThat(deckId).isEqualTo(7);
    }

    @Test
    void aPathVariableTheParserRejectsIsA400NamingTheVariable() {
        WebRequest request = TestRequest.get("/decks/nine")
                .pathParam("deckId", "nine")
                .build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.pathParam("deckId", Integer::parseInt))
                .satisfies(e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("{deckId}").contains("nine");
                });
    }

    /** Only a rejected value is the request's fault: anything else the parser throws is the parser's. */
    @Test
    void anotherExceptionFromTheParserIsNotTurnedIntoA400() {
        WebRequest request = TestRequest.get("/cards").queryParam("owner", "anything").build();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> request.param("owner", value -> {
                    throw new IllegalStateException("the parser is broken");
                }));
    }
}
