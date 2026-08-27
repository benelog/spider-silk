package net.benelog.spidersilk.jetty.websocket;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

/**
 * One WebSocket connection, from the upgrade to the close.
 *
 * <p>A {@link WebSocketFactory} builds one of these per connection, so an
 * implementation may hold per-connection state in fields without synchronising:
 * the callbacks below are never called concurrently for the same connection.
 *
 * <pre>{@code
 * final class EchoSocket implements WebSocketHandler {
 *     @Override
 *     public void onText(Session session, String message) {
 *         session.sendText(message, Callback.NOOP);
 *     }
 * }
 * }</pre>
 *
 * <p>Every method has a do-nothing default, so an implementation overrides only
 * what it answers. The {@link Session} is passed to each one rather than handed
 * out once, so nothing has to be remembered in a field to reply.
 *
 * <p>{@code Session} is Jetty's own — this module is Jetty-only by construction
 * and does not wrap it. {@code sendText} and {@code sendBinary} are
 * asynchronous and take a {@link Callback}; {@link Callback#NOOP} is the
 * fire-and-forget form, and a real one is how a send failure is noticed.
 */
public interface WebSocketHandler {

    /** The upgrade succeeded and frames may now be sent. */
    default void onOpen(Session session) {
    }

    /** A complete text message. Fragments are joined before this is called. */
    default void onText(Session session, String message) {
    }

    /**
     * A complete binary message. The buffer is only valid for the duration of
     * the call — it is returned to Jetty's pool as soon as this returns, so
     * anything kept beyond it must be copied.
     */
    default void onBinary(Session session, ByteBuffer message) {
    }

    /**
     * The connection is closed, whichever side closed it and whether or not it
     * closed cleanly. Always called once, and always after {@link #onError}
     * when both apply.
     */
    default void onClose(Session session, int status, String reason) {
    }

    /**
     * A protocol error, a failed read or write, or an exception thrown by one
     * of the methods above. {@link #onClose} still follows.
     */
    default void onError(Session session, Throwable cause) {
    }
}
