package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersilk.test.WebTest;

/** The default template engine, and the suffix a name is looked up under. */
class TemplatesTest {

    @Test
    void anAppRendersWithJteWithoutBeingConfigured() {
        App app = new App().get("/hello",
                req -> WebResponse.template("greeting", Model.of("name", "Silk")));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/hello");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("<p>Hello, Silk!</p>\n");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .isEqualTo("text/html;charset=utf-8");
        });
    }

    /**
     * The directory jte compiles into is gone once the JVM exits. Each renderer
     * created one and left it, so every run left a {@code jte-classes*}
     * directory behind. The render runs in a JVM of its own, with a temp
     * directory of its own, so the test can watch that JVM exit.
     */
    @Test
    void theCompilationDirectoryIsDeletedWhenTheJvmExits(@TempDir Path tmp) throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + tmp,
                "-cp", System.getProperty("java.class.path"),
                RenderOnce.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(output).isTrue();

        assertThat(output).contains("<p>Hello, Silk!</p>").contains("jte-classes directories: 1");
        try (Stream<Path> left = Files.list(tmp)) {
            assertThat(left.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith("jte-classes"));
        }
    }

    /** Renders one template and reports the compilation directories it sees before exiting. */
    static final class RenderOnce {

        public static void main(String[] args) throws Exception {
            StringWriter out = new StringWriter();
            new JteTemplates(JteTemplates.DEFAULT_ROOT).render("greeting", Model.of("name", "Silk"), out);
            System.out.println(out);
            try (Stream<Path> created = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
                System.out.println("jte-classes directories: "
                        + created.filter(path -> path.getFileName().toString().startsWith("jte-classes")).count());
            }
        }
    }

    @Test
    void theModelIsEscapedByTheDefaultEngine() {
        App app = new App().get("/hello",
                req -> WebResponse.template("greeting", Model.of("name", "<script>")));

        WebTest.test(app, client ->
                assertThat(client.get("/hello").body()).isEqualTo("<p>Hello, &lt;script&gt;!</p>\n"));
    }

    @Test
    void aRootAndSuffixOfItsOwnReplaceTheDefaults() {
        App app = new App()
                .templates(new JteTemplates("templates").suffix(".html"))
                .get("/hello", req -> WebResponse.template("greeting", Model.of("name", "Silk")));

        WebTest.test(app, client ->
                assertThat(client.get("/hello").body()).isEqualTo("<p>Howdy, Silk!</p>\n"));
    }

    @Test
    void theSuffixIsAppendedNotChecked() {
        StringWriter out = new StringWriter();

        assertThatThrownBy(() -> new JteTemplates("jte")
                .render("greeting.jte", Model.of("name", "Silk"), out))
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
                req -> WebResponse.template("greeting", Model.of("name", req.pathParam("name"))));

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
                .get("/hello", req -> WebResponse.template("nothing-here"))
                .exception(Exception.class,
                        (req, e) -> WebResponse.text("caught").status(HttpStatus.BAD_REQUEST));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/hello");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo("caught");
        });
    }

}
