package net.benelog.spidersilk.opentelemetry.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link TracedApplication} under the real OpenTelemetry Java agent, with this module's jar
 * as its extension, and reads the server spans the agent's logging exporter writes.
 */
class RouteInstrumentationTest {

    private static final Pattern PORT = Pattern.compile("PORT (\\d+)");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Test
    void namesTheSpanAfterTheMatchedRoute() throws Exception {
        try (Traced traced = Traced.start()) {
            traced.get("/decks/7");
            traced.get("/api/cards/3");

            traced.awaitSpan("GET /decks/{deckId}");
            traced.awaitSpan("GET /api/cards/{cardId}");
            assertThat(traced.output()).contains("http.route=/decks/{deckId}");
            assertThat(traced.output()).contains("http.route=/api/cards/{cardId}");
        }
    }

    @Test
    void leavesARequestNoRouteMatchedAtTheServletMapping() throws Exception {
        try (Traced traced = Traced.start()) {
            assertThat(traced.get("/nothing-here")).isEqualTo(404);

            traced.awaitSpan("GET /*");
        }
    }

    @Test
    void isSwitchedOffLikeAnyAgentInstrumentation() throws Exception {
        try (Traced traced = Traced.start("-Dotel.instrumentation.spider-silk.enabled=false")) {
            traced.get("/decks/7");

            traced.awaitSpan("GET /*");
            assertThat(traced.output()).doesNotContain("GET /decks/{deckId}");
        }
    }

    /** The application's JVM, with the agent attached and its output collected. */
    private static final class Traced implements AutoCloseable {

        private final Process process;
        // Written by the reader thread and read by the test's, so the synchronization is the point.
        @SuppressWarnings("JdkObsolete")
        private final StringBuffer output = new StringBuffer();
        private final HttpClient client = HttpClient.newHttpClient();
        private final int port;

        private Traced(Process process) throws InterruptedException {
            this.process = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } catch (java.io.IOException ignored) {
                    // The process ended; what was read is kept.
                }
            });
            reader.setDaemon(true);
            reader.start();
            this.port = Integer.parseInt(await(PORT).group(1));
        }

        static Traced start(String... extraOptions) throws Exception {
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-javaagent:" + System.getProperty("test.otel.agent"));
            command.add("-Dotel.javaagent.extensions=" + System.getProperty("test.otel.extension"));
            command.add("-Dotel.traces.exporter=logging");
            command.add("-Dotel.metrics.exporter=none");
            command.add("-Dotel.logs.exporter=none");
            command.add("-Dotel.bsp.schedule.delay=50");
            command.addAll(List.of(extraOptions));
            command.add("-cp");
            command.add(System.getProperty("test.app.classpath"));
            command.add(TracedApplication.class.getName());
            return new Traced(new ProcessBuilder(command).redirectErrorStream(true).start());
        }

        int get(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }

        /** The logging exporter writes a span as {@code 'name' : traceId spanId KIND ...}. */
        void awaitSpan(String name) throws InterruptedException {
            await(Pattern.compile("'" + Pattern.quote(name) + "' : \\w+ \\w+ SERVER"));
        }

        String output() {
            return output.toString();
        }

        private Matcher await(Pattern pattern) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                Matcher matcher = pattern.matcher(output);
                if (matcher.find()) {
                    return matcher;
                }
                if (!process.isAlive()) {
                    break;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("No match for " + pattern + " in the agent's output:\n" + output);
        }

        @Override
        public void close() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }
}
