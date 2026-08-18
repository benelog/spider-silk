package spidersilk;

import org.junit.jupiter.api.Test;

import spidersilk.test.TestRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Optional typed parameters: absent answers the default, garbage still answers 400. */
class OptionalParamsTest {

    enum Direction { FRONT, BACK }

    @Test
    void anAbsentParameterAnswersTheDefault() {
        WebRequest request = TestRequest.get("/decks").build();

        assertEquals(1L, request.paramLong("page", 1));
        assertEquals(Direction.FRONT,
                request.paramEnum("direction", Direction.class, Direction.FRONT));
    }

    @Test
    void aPresentParameterIsParsed() {
        WebRequest request = TestRequest.get("/decks")
                .queryParam("page", "3")
                .queryParam("direction", "BACK")
                .build();

        assertEquals(3L, request.paramLong("page", 1));
        assertEquals(Direction.BACK,
                request.paramEnum("direction", Direction.class, Direction.FRONT));
    }

    /** The default covers absence only: a value that is there but wrong is a 400. */
    @Test
    void anUnparseableValueIsStillA400RatherThanTheDefault() {
        WebRequest request = TestRequest.get("/decks")
                .queryParam("page", "x")
                .queryParam("direction", "SIDEWAYS")
                .build();

        HttpException notANumber = assertThrows(HttpException.class,
                () -> request.paramLong("page", 1));
        assertEquals(400, notANumber.status());

        HttpException noSuchConstant = assertThrows(HttpException.class,
                () -> request.paramEnum("direction", Direction.class, Direction.FRONT));
        assertEquals(400, noSuchConstant.status());
    }
}
