package spidersilk.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import spidersilk.HttpException;
import spidersilk.HttpStatus;
import spidersilk.WebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a handler reads off a built request, and where it is meant to reject one. */
class TestRequestTest {

    @Test
    void answersTheMethodAndPath() {
        WebRequest request = TestRequest.post("/api/decks").build();

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/decks");
    }

    @Test
    void refusesAQueryStringInThePath() {
        assertThatThrownBy(() -> TestRequest.get("/decks?page=2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryParam");
    }

    // ---- Path variables ----

    @Test
    void suppliesThePathVariablesARouteWouldHaveResolved() {
        WebRequest request = TestRequest.get("/decks/3").pathParam("deckId", "3").build();

        assertThat(request.pathParamLong("deckId")).isEqualTo(3L);
    }

    @Test
    void aPathVariableThatIsNotANumberIsA400() {
        WebRequest request = TestRequest.get("/decks/x").pathParam("deckId", "x").build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.pathParamLong("deckId"))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---- Parameters ----

    @Test
    void aMissingRequiredParameterIsA400() {
        WebRequest request = TestRequest.post("/decks").build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.param("name"))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void repeatedParametersKeepTheirOrder() {
        WebRequest request = TestRequest.post("/cards")
                .formParam("tag", "verb")
                .formParam("tag", "irregular")
                .build();

        assertThat(request.params("tag")).isEqualTo(List.of("verb", "irregular"));
        assertThat(request.param("tag")).isEqualTo("verb");
    }

    /**
     * The container merges the query string with the form body, and
     * {@code formParam} is what is left once the query values are taken out —
     * the one behaviour a mock that keeps a single parameter map cannot show.
     */
    @Test
    void queryAndFormParametersOfTheSameNameStaySeparate() {
        WebRequest request = TestRequest.post("/decks")
                .queryParam("name", "from-query")
                .formParam("name", "from-form")
                .build();

        assertThat(request.params("name")).isEqualTo(List.of("from-query", "from-form"));
        assertThat(request.queryParam("name")).isEqualTo("from-query");
        assertThat(request.formParam("name")).isEqualTo("from-form");
    }

    @Test
    void aParameterSetOnlyOnOneSideIsAbsentFromTheOther() {
        WebRequest request = TestRequest.post("/decks").formParam("name", "English").build();

        assertThat(request.queryParam("name")).isNull();
        assertThat(request.formParam("name")).isEqualTo("English");
        assertThat(request.param("name")).isEqualTo("English");
    }

    // ---- Headers ----

    @Test
    void headerLookupIgnoresCase() {
        WebRequest request = TestRequest.get("/api/decks")
                .header("X-Api-Key", "secret")
                .build();

        assertThat(request.header("x-api-key")).isEqualTo("secret");
        assertThat(request.header("X-Other")).isNull();
    }

    // ---- Body ----

    @Test
    void readsTheBodyBack() {
        WebRequest request = TestRequest.post("/decks").body("plain text").build();

        assertThat(request.body()).isEqualTo("plain text");
    }

    @Test
    void jsonBodySetsTheContentTypeAndParses() {
        WebRequest request = TestRequest.post("/api/decks")
                .jsonBody("{\"name\": \"Spanish\"}")
                .build();

        assertThat(request.header("Content-Type")).isEqualTo("application/json");
        assertThat(request.bodyJson().asObject().getString("name")).isEqualTo("Spanish");
    }

    @Test
    void aBodyTheReaderRejectsIsA400() {
        WebRequest request = TestRequest.post("/api/decks")
                .jsonBody("{\"title\": \"Spanish\"}")
                .build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.bodyJson(json -> json.asObject().getString("name")))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---- Cookies ----

    @Test
    void readsTheCookiesTheClientSent() {
        WebRequest request = TestRequest.get("/decks").cookie("theme", "dark").build();

        assertThat(request.cookie("theme")).isEqualTo("dark");
        assertThat(request.cookies()).hasSize(1);
    }

    @Test
    void aRequestWithoutCookiesAnswersNull() {
        WebRequest request = TestRequest.get("/decks").build();

        assertThat(request.cookie("theme")).isNull();
        assertThat(request.cookies()).isEmpty();
    }

    // ---- Session ----

    @Test
    void readsASessionAttributePutThereInAdvance() {
        WebRequest request = TestRequest.get("/decks").sessionAttr("userId", 7L).build();

        assertThat((Long) request.sessionAttr("userId")).isEqualTo(7L);
    }

    @Test
    void aRequestWithoutASessionAnswersNullRatherThanCreatingOne() {
        WebRequest request = TestRequest.get("/decks").build();

        assertThat((Object) request.sessionAttr("userId")).isNull();
    }

    @Test
    void whatAHandlerPutsInTheSessionIsReadableAfterwards() {
        WebRequest request = TestRequest.get("/decks").build();

        request.sessionAttr("userId", 7L);

        assertThat((Long) request.sessionAttr("userId")).isEqualTo(7L);
    }

    // ---- Uploads ----

    @Test
    void readsAnUploadedFile() {
        WebRequest request = TestRequest.post("/decks/3/import")
                .file("file", "cards.csv", "front,back\nhola,hello\n")
                .build();

        assertThat(request.file("file").fileName()).isEqualTo("cards.csv");
        assertThat(request.file("file").asText()).isEqualTo("front,back\nhola,hello\n");
    }

    @Test
    void askingForAFileThatWasNotUploadedIsA400() {
        WebRequest request = TestRequest.post("/decks/3/import")
                .file("other", "cards.csv", "front,back\n")
                .build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.file("file"))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** No file at all means the request is not multipart, which is the other 400. */
    @Test
    void askingForAFileOnARequestWithNoneIsA400() {
        WebRequest request = TestRequest.post("/decks/3/import").build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.file("file"))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
