package flashcard;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.WebTest;

import flashcard.repository.RepositoryTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which status a failure answers with over HTTP: a lookup that found nothing
 * is the one 404 FlashcardContext maps, and a body the framework rejects is
 * its own 400, with no exception handler of the application's involved.
 */
class ErrorStatusTest extends RepositoryTestSupport {

    @Test
    void aMissingDeckIsA404NamingIt() {
        WebTest.test(new FlashcardContext(dataSource).createApp(), client -> {
            var response = client.get("/api/decks/9999/cards");

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEqualTo("Deck not found: 9999");
        });
    }

    @Test
    void aBodyThatIsNotJsonIsA400() {
        WebTest.test(new FlashcardContext(dataSource).createApp(), client ->
                assertThat(client.postJson("/api/decks", "{\"name\":").statusCode())
                        .isEqualTo(400));
    }

    @Test
    void aBodyWithoutTheKeyIsA400() {
        WebTest.test(new FlashcardContext(dataSource).createApp(), client ->
                assertThat(client.postJson("/api/decks", "{}").statusCode())
                        .isEqualTo(400));
    }
}
