package net.benelog.spidersilk.server;

import net.benelog.spidersilk.App;

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
     * Creates the server, configured but not yet started.
     *
     * @param app  the application to serve
     * @param port the port passed to {@link App#start(int)}
     * @return the server {@link App#start(int)} will start
     */
    WebServer create(App app, int port);
}
