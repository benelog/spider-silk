package net.benelog.spidersilk;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Optional typed parameters: absent answers the default, garbage still answers 400. */
class OptionalParamsTest {

    enum Direction { FRONT, BACK }

    @Test
    void anAbsentParameterAnswersTheDefault() {
        WebRequest request = TestRequest.get("/decks").build();

        assertThat(request.paramLong("page", 1)).isEqualTo(1L);
        assertThat(request.paramEnum("direction", Direction.class, Direction.FRONT))
                .isEqualTo(Direction.FRONT);
        assertThat(request.paramBoolean("archived", true)).isTrue();
    }

    @Test
    void aPresentParameterIsParsed() {
        WebRequest request = TestRequest.get("/decks")
                .queryParam("page", "3")
                .queryParam("direction", "BACK")
                .queryParam("archived", "TRUE")
                .queryParam("flipped", "false")
                .build();

        assertThat(request.paramLong("page", 1)).isEqualTo(3L);
        assertThat(request.paramEnum("direction", Direction.class, Direction.FRONT))
                .isEqualTo(Direction.BACK);
        assertThat(request.paramBoolean("archived", false)).isTrue();
        assertThat(request.paramBoolean("flipped")).isFalse();
    }

    /** The optional string with no default: null for absence, the value, from query or form alike. */
    @Test
    void paramOrNullAnswersNullForAnAbsentParameter() {
        WebRequest request = TestRequest.post("/decks")
                .queryParam("q", "verbs")
                .formParam("name", "Spanish")
                .build();

        assertThat(request.paramOrNull("q")).isEqualTo("verbs");
        assertThat(request.paramOrNull("name")).isEqualTo("Spanish");
        assertThat(request.paramOrNull("absent")).isNull();
    }

    /** An empty value was sent, so it is the value and not an absence. */
    @Test
    void paramOrNullKeepsAnEmptyValue() {
        WebRequest request = TestRequest.get("/decks").queryParam("q", "").build();

        assertThat(request.paramOrNull("q")).isEmpty();
    }

    /**
     * Long.parseLong takes any digit Character.digit knows, so full-width and
     * Arabic-Indic digits read as numbers, and /decks/１ was deck 1. Only an
     * optional sign and ASCII digits are a number here.
     */
    @Test
    void digitsOutsideAsciiAreNotANumber() {
        WebRequest request = TestRequest.get("/decks/\uFF11")
                .pathParam("deckId", "\uFF11")
                .queryParam("n", "\uFF11\uFF12")
                .queryParam("m", "\u0661")
                .queryParam("negative", "-12")
                .queryParam("sign", "+7")
                .queryParam("bare", "-")
                .build();

        for (Runnable read : new Runnable[] {
                () -> request.pathParamLong("deckId"),
                () -> request.paramLong("n"),
                () -> request.paramLong("m", 0),
                () -> request.paramLong("bare")}) {
            assertThatExceptionOfType(HttpException.class)
                    .isThrownBy(read::run)
                    .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(request.paramLong("negative")).isEqualTo(-12L);
        assertThat(request.paramLong("sign")).isEqualTo(7L);
    }

    /** The default covers absence only: a value that is there but wrong is a 400. */
    @Test
    void anUnparseableValueIsStillA400RatherThanTheDefault() {
        WebRequest request = TestRequest.get("/decks")
                .queryParam("page", "x")
                .queryParam("direction", "SIDEWAYS")
                .queryParam("archived", "yes")
                .build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.paramLong("page", 1))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.paramEnum("direction", Direction.class, Direction.FRONT))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // "yes" is not false. Boolean.parseBoolean would have read it as one.
        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.paramBoolean("archived", false))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.paramBoolean("absent"))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
