package net.benelog.spidersilk.test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.UploadedFile;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonWriter;

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
    void jsonBodyTakesATree() {
        WebRequest request = TestRequest.post("/api/decks")
                .jsonBody(Json.obj().put("name", "Spanish"))
                .build();

        assertThat(request.header("Content-Type")).isEqualTo("application/json");
        assertThat(request.bodyJson().asObject().getString("name")).isEqualTo("Spanish");
    }

    @Test
    void jsonBodyTakesTheApplicationsOwnWriter() {
        JsonWriter<NewDeck> writer = deck -> Json.obj().put("name", deck.name());

        WebRequest request = TestRequest.post("/api/decks")
                .jsonBody(new NewDeck("Spanish"), writer)
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

    @Test
    void oneFieldCarriesSeveralFiles() {
        WebRequest request = TestRequest.post("/decks/3/import")
                .file("pages", "one.txt", "1")
                .file("pages", "two.txt", "2")
                .file("cover", "cover.png", "png")
                .build();

        assertThat(request.files("pages")).extracting(UploadedFile::fileName)
                .containsExactly("one.txt", "two.txt");
        assertThat(request.files("cover")).hasSize(1);
        assertThat(request.files("missing")).isEmpty();
        // The first of them is what a container answers for the name alone.
        assertThat(request.file("pages").asText()).isEqualTo("1");
    }

    @Test
    void anAbsentOptionalUploadIsNull() {
        WebRequest withoutIt = TestRequest.post("/profile")
                .file("other", "note.txt", "x")
                .build();
        WebRequest withIt = TestRequest.post("/profile")
                .file("avatar", "me.png", "png")
                .build();

        assertThat(withoutIt.fileOrNull("avatar")).isNull();
        assertThat(TestRequest.post("/profile").build().fileOrNull("avatar")).isNull();
        assertThat(withIt.fileOrNull("avatar").fileName()).isEqualTo("me.png");
    }

    /** A file input a browser sent with nothing chosen: a part, and no file. */
    @Test
    void aPartWithNoFileNameIsNotAnUpload() {
        WebRequest request = TestRequest.post("/profile")
                .file("avatar", "", "")
                .build();

        assertThat(request.fileOrNull("avatar")).isNull();
        assertThat(request.files("avatar")).isEmpty();
        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.file("avatar"))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void anUploadIsReadAsAStreamAndWrittenToAFile(@TempDir Path dir) throws Exception {
        WebRequest request = TestRequest.post("/decks/3/import")
                .file("csv", "cards.csv", "front,back\n")
                .build();
        Path target = dir.resolve("saved.csv");

        try (InputStream in = request.file("csv").inputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("front,back\n");
        }
        request.file("csv").writeTo(target);

        assertThat(Files.readString(target)).isEqualTo("front,back\n");
    }

    /** The body of a request, as the application would declare it. */
    private record NewDeck(String name) {
    }
}
