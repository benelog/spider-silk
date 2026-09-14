package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestRequest;
import net.benelog.spidersilk.test.WebTest;

/** A parser on a named source: the query string or the form body, never the merge of the two. */
class SourceParamsTest {

    @Test
    void eachSourceIsReadThroughAParser() {
        WebRequest request = TestRequest.post("/cards")
                .queryParam("since", "2026-03-01")
                .formParam("owner", "5a1f3c2e-0000-4000-8000-000000000001")
                .build();

        LocalDate since = request.queryParam("since", LocalDate::parse);
        UUID owner = request.formParam("owner", UUID::fromString);

        assertThat(since).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(owner).isEqualTo(UUID.fromString("5a1f3c2e-0000-4000-8000-000000000001"));
    }

    /** The other source carrying the name is not the one asked for. */
    @Test
    void aValueInTheOtherSourceIsStillMissing() {
        WebRequest request = TestRequest.post("/cards")
                .queryParam("page", "2")
                .formParam("due", "2026-03-01")
                .build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.formParam("page", Integer::parseInt))
                .satisfies(e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Missing required form field: page");
                });
        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.queryParam("due", LocalDate::parse))
                .satisfies(e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("Missing required query parameter: due");
                });
    }

    @Test
    void theDefaultCoversAbsenceInThatSourceOnly() {
        WebRequest request = TestRequest.post("/cards").formParam("page", "7").build();

        int fromQuery = request.queryParam("page", Integer::parseInt, 1);
        int fromForm = request.formParam("page", Integer::parseInt, 1);
        int size = request.formParam("size", Integer::parseInt, 20);

        assertThat(fromQuery).isEqualTo(1);
        assertThat(fromForm).isEqualTo(7);
        assertThat(size).isEqualTo(20);
    }

    /** The default covers absence only: a value the parser rejects is a 400 in either source. */
    @Test
    void aValueTheParserRejectsIsA400WithOrWithoutADefault() {
        WebRequest request = TestRequest.post("/cards")
                .queryParam("page", "x")
                .formParam("due", "2026-13-01")
                .build();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.queryParam("page", Integer::parseInt, 1))
                .satisfies(e -> assertThat(e.getMessage()).contains("page").contains("x"));
        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.formParam("due", LocalDate::parse))
                .satisfies(e -> assertThat(e.getMessage()).contains("due").contains("2026-13-01"));
        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> request.formParam("due", LocalDate::parse, LocalDate.EPOCH))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** Only the two rejecting exception types are the request's fault; anything else is the parser's. */
    @Test
    void anyOtherParserFailureIsNotA400() {
        WebRequest request = TestRequest.get("/cards").queryParam("page", "2").build();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> request.queryParam("page", value -> {
                    throw new IllegalStateException("broken parser");
                }));
    }

    /**
     * One name in both sources, through a real container, where the servlet API
     * hands the two back merged: each reader still answers with its own.
     */
    @Test
    void aNameInBothSourcesIsReadFromTheOneNamed() {
        App app = new App().post("/cards", req -> WebResponse.text(
                req.queryParam("page", Integer::parseInt) + " " + req.formParam("page", Integer::parseInt)));

        WebTest.test(app, client -> {
            var response = client.send(builder -> builder
                    .uri(URI.create(client.url("/cards?page=2")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("page=9")));

            assertThat(response.body()).isEqualTo("2 9");
        });
    }
}
