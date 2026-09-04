package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.WebTest;

/** The default template engine, and the suffix a name is looked up under. */
class TemplatesTest {

    @Test
    void anAppRendersWithJteWithoutBeingConfigured() {
        App app = new App().get("/hello",
                req -> WebResponse.template("greeting", Map.of("name", "Silk")));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/hello");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("<p>Hello, Silk!</p>\n");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .isEqualTo("text/html;charset=utf-8");
        });
    }

    @Test
    void theModelIsEscapedByTheDefaultEngine() {
        App app = new App().get("/hello",
                req -> WebResponse.template("greeting", Map.of("name", "<script>")));

        WebTest.test(app, client ->
                assertThat(client.get("/hello").body()).isEqualTo("<p>Hello, &lt;script&gt;!</p>\n"));
    }

    @Test
    void aRootAndSuffixOfItsOwnReplaceTheDefaults() {
        App app = new App()
                .templates(new JteTemplates("templates").suffix(".html"))
                .get("/hello", req -> WebResponse.template("greeting", Map.of("name", "Silk")));

        WebTest.test(app, client ->
                assertThat(client.get("/hello").body()).isEqualTo("<p>Howdy, Silk!</p>\n"));
    }

    @Test
    void theSuffixIsAppendedNotChecked() {
        StringWriter out = new StringWriter();

        assertThatThrownBy(() -> new JteTemplates("jte")
                .render("greeting.jte", Map.of("name", "Silk"), out))
                .hasMessageContaining("greeting.jte.jte");
    }

    /**
     * The engine is built on the first render, and every render after it goes
     * through the same accessor. Several requests at once therefore have to
     * come back with their own page, not one another's and not a deadlock.
     */
    @Test
    void concurrentRendersEachGetTheirOwnPage() {
        App app = new App().get("/hello/{name}",
                req -> WebResponse.template("greeting", Map.of("name", req.pathParam("name"))));

        WebTest.test(app, client -> {
            int callers = 16;
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            try {
                CyclicBarrier startTogether = new CyclicBarrier(callers);
                List<CompletableFuture<String>> answers = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    String name = "caller" + i;
                    answers.add(CompletableFuture.supplyAsync(() -> {
                        awaitAll(startTogether);
                        return client.get("/hello/" + name).body();
                    }, pool));
                }
                for (int i = 0; i < callers; i++) {
                    assertThat(answers.get(i).join())
                            .isEqualTo("<p>Hello, caller" + i + "!</p>\n");
                }
            } finally {
                pool.shutdownNow();
            }
        });
    }

    /** The barrier every caller waits on, so the renders really do overlap. */
    private static void awaitAll(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the other callers", e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("The callers never lined up", e);
        }
    }

    @Test
    void aTemplateThatThrowsReachesTheExceptionHandler() {
        App app = new App()
                .get("/hello", req -> WebResponse.template("nothing-here", Map.of()))
                .exception(Exception.class,
                        (req, e) -> WebResponse.text("caught").status(HttpStatus.BAD_REQUEST));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/hello");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo("caught");
        });
    }

}
