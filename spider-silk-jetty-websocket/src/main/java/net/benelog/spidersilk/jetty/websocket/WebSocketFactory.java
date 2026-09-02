package net.benelog.spidersilk.jetty.websocket;

import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;

/**
 * Builds the {@link WebSocketHandler} for one upgrade request.
 *
 * <p>Called once per connection, before the handshake is answered, which is
 * where the request is still an ordinary HTTP request: headers, cookies, and
 * the query string are all readable, and a sub-protocol can be accepted on the
 * response.
 *
 * <pre>{@code
 * new WebSockets().at("/chat", (request, response) -> {
 *     if (request.hasSubProtocol("chat")) {
 *         response.setAcceptedSubProtocol("chat");
 *     }
 *     return new ChatSocket(rooms);
 * });
 * }</pre>
 *
 * <p>Returning null refuses the upgrade, answered with 403 — or with the status
 * the factory set on the response, if that is an error status.
 * Jetty leaves a refused upgrade unanswered and the client waiting; this module
 * answers it, which is one of the reasons it exists.
 */
@FunctionalInterface
public interface WebSocketFactory {

    /**
     * The handler for one connection, or null to refuse the upgrade.
     *
     * @param request  the upgrade request, still an ordinary HTTP request
     * @param response the handshake response, where a sub-protocol is accepted
     * @return the handler that takes the connection, or null to refuse it
     */
    WebSocketHandler create(ServerUpgradeRequest request, ServerUpgradeResponse response);
}
