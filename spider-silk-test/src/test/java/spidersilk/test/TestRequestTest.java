package spidersilk.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import spidersilk.HttpException;
import spidersilk.WebRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a handler reads off a built request, and where it is meant to reject one. */
class TestRequestTest {

    @Test
    void answersTheMethodAndPath() {
        WebRequest request = TestRequest.post("/api/decks").build();

        assertEquals("POST", request.method());
        assertEquals("/api/decks", request.path());
    }

    @Test
    void refusesAQueryStringInThePath() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TestRequest.get("/decks?page=2"));
        assertTrue(e.getMessage().contains("queryParam"), e.getMessage());
    }

    // ---- Path variables ----

    @Test
    void suppliesThePathVariablesARouteWouldHaveResolved() {
        WebRequest request = TestRequest.get("/decks/3").pathParam("deckId", "3").build();

        assertEquals(3L, request.pathParamLong("deckId"));
    }

    @Test
    void aPathVariableThatIsNotANumberIsA400() {
        WebRequest request = TestRequest.get("/decks/x").pathParam("deckId", "x").build();

        HttpException e = assertThrows(HttpException.class,
                () -> request.pathParamLong("deckId"));
        assertEquals(400, e.status());
    }

    // ---- Parameters ----

    @Test
    void aMissingRequiredParameterIsA400() {
        WebRequest request = TestRequest.post("/decks").build();

        assertEquals(400, assertThrows(HttpException.class, () -> request.param("name")).status());
    }

    @Test
    void repeatedParametersKeepTheirOrder() {
        WebRequest request = TestRequest.post("/cards")
                .formParam("tag", "verb")
                .formParam("tag", "irregular")
                .build();

        assertEquals(List.of("verb", "irregular"), request.params("tag"));
        assertEquals("verb", request.param("tag"));
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

        assertEquals(List.of("from-query", "from-form"), request.params("name"));
        assertEquals("from-query", request.queryParam("name"));
        assertEquals("from-form", request.formParam("name"));
    }

    @Test
    void aParameterSetOnlyOnOneSideIsAbsentFromTheOther() {
        WebRequest request = TestRequest.post("/decks").formParam("name", "English").build();

        assertNull(request.queryParam("name"));
        assertEquals("English", request.formParam("name"));
        assertEquals("English", request.param("name"));
    }

    // ---- Headers ----

    @Test
    void headerLookupIgnoresCase() {
        WebRequest request = TestRequest.get("/api/decks")
                .header("X-Api-Key", "secret")
                .build();

        assertEquals("secret", request.header("x-api-key"));
        assertNull(request.header("X-Other"));
    }

    // ---- Body ----

    @Test
    void readsTheBodyBack() {
        WebRequest request = TestRequest.post("/decks").body("plain text").build();

        assertEquals("plain text", request.body());
    }

    @Test
    void jsonBodySetsTheContentTypeAndParses() {
        WebRequest request = TestRequest.post("/api/decks")
                .jsonBody("{\"name\": \"Spanish\"}")
                .build();

        assertEquals("application/json", request.header("Content-Type"));
        assertEquals("Spanish", request.bodyJson().asObject().getString("name"));
    }

    @Test
    void aBodyTheReaderRejectsIsA400() {
        WebRequest request = TestRequest.post("/api/decks")
                .jsonBody("{\"title\": \"Spanish\"}")
                .build();

        HttpException e = assertThrows(HttpException.class,
                () -> request.bodyJson(json -> json.asObject().getString("name")));
        assertEquals(400, e.status());
    }

    // ---- Cookies ----

    @Test
    void readsTheCookiesTheClientSent() {
        WebRequest request = TestRequest.get("/decks").cookie("theme", "dark").build();

        assertEquals("dark", request.cookie("theme"));
        assertEquals(1, request.cookies().size());
    }

    @Test
    void aRequestWithoutCookiesAnswersNull() {
        WebRequest request = TestRequest.get("/decks").build();

        assertNull(request.cookie("theme"));
        assertTrue(request.cookies().isEmpty());
    }

    // ---- Session ----

    @Test
    void readsASessionAttributePutThereInAdvance() {
        WebRequest request = TestRequest.get("/decks").sessionAttr("userId", 7L).build();

        assertEquals(7L, (Long) request.sessionAttr("userId"));
    }

    @Test
    void aRequestWithoutASessionAnswersNullRatherThanCreatingOne() {
        WebRequest request = TestRequest.get("/decks").build();

        assertNull(request.sessionAttr("userId"));
    }

    @Test
    void whatAHandlerPutsInTheSessionIsReadableAfterwards() {
        WebRequest request = TestRequest.get("/decks").build();

        request.sessionAttr("userId", 7L);

        assertEquals(7L, (Long) request.sessionAttr("userId"));
    }

    // ---- Uploads ----

    @Test
    void readsAnUploadedFile() {
        WebRequest request = TestRequest.post("/decks/3/import")
                .file("file", "cards.csv", "front,back\nhola,hello\n")
                .build();

        assertEquals("cards.csv", request.file("file").fileName());
        assertEquals("front,back\nhola,hello\n", request.file("file").asText());
    }

    @Test
    void askingForAFileThatWasNotUploadedIsA400() {
        WebRequest request = TestRequest.post("/decks/3/import")
                .file("other", "cards.csv", "front,back\n")
                .build();

        assertEquals(400, assertThrows(HttpException.class, () -> request.file("file")).status());
    }

    /** No file at all means the request is not multipart, which is the other 400. */
    @Test
    void askingForAFileOnARequestWithNoneIsA400() {
        WebRequest request = TestRequest.post("/decks/3/import").build();

        assertEquals(400, assertThrows(HttpException.class, () -> request.file("file")).status());
    }
}
