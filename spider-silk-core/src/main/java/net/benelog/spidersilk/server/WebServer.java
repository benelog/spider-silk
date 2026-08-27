package net.benelog.spidersilk.server;

/**
 * An embedded HTTP server running an {@link net.benelog.spidersilk.App}.
 * The bundled implementation is {@link JettyServer}; another server is plugged in
 * by implementing this interface and handing a {@link WebServerFactory} to
 * {@link net.benelog.spidersilk.App#server(WebServerFactory)}.
 */
public interface WebServer {

    /** Binds the port and starts serving. Fails if the server is already running. */
    void start();

    /** Stops the server. Doing nothing on an already stopped server is fine. */
    void stop();

    /** Blocks the calling thread until the server stops. */
    void join();

    /**
     * The port actually bound. Starting on port 0 picks a free port,
     * and this is how the test finds out which one.
     */
    int port();
}
