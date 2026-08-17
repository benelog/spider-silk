package spidersilk.server;

import spidersilk.App;

/**
 * Creates the server that {@link App#start(int)} runs.
 * The default is {@code (app, port) -> new JettyServer(app).port(port)}.
 *
 * <p>Replace it to swap the server implementation, or to keep Jetty while
 * reaching into its settings:
 *
 * <pre>{@code
 * app.server((a, port) -> new JettyServer(a)
 *                 .port(port)
 *                 .customizeServer(server -> server.setStopTimeout(5_000)))
 *    .start(8080);
 * }</pre>
 */
@FunctionalInterface
public interface WebServerFactory {

    /**
     * @param app  the application to serve
     * @param port the port passed to {@link App#start(int)}
     */
    WebServer create(App app, int port);
}
