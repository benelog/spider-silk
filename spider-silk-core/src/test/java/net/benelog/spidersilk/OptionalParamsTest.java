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
