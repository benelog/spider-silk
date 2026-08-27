package spidersilk.ws;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;

/**
 * The one class Jetty ever sees as an endpoint.
 *
 * <p>Jetty binds a listener's callbacks by looking its methods up and taking a
 * {@code MethodHandle} to each, caching the result per endpoint class. Every
 * connection this module opens is this class, so what Jetty looks up is this
 * class and no other: the {@link WebSocketHandler} an application writes is
 * reached through an interface call and is never reflected over, whatever its
 * shape.
 *
 * <p>Auto-demanding, so a handler never calls {@link Session#demand()}: the
 * next message is asked for as soon as the callback returns.
 *
 * <p>Public because Jetty reaches an endpoint's callbacks through
 * {@code MethodHandles.publicLookup()}, which a package-private class refuses.
 * Nothing constructs one but this module, and nothing here is API to call.
 */
public final class SessionListener extends Session.Listener.AbstractAutoDemanding {

    private final WebSocketHandler handler;

    SessionListener(WebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void onWebSocketOpen(Session session) {
        super.onWebSocketOpen(session);
        handler.onOpen(session);
    }

    @Override
    public void onWebSocketText(String message) {
        handler.onText(getSession(), message);
    }

    @Override
    public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
        try {
            handler.onBinary(getSession(), payload);
            callback.succeed();
        } catch (Throwable failure) {
            callback.fail(failure);
        }
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        handler.onError(getSession(), cause);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        try {
            handler.onClose(getSession(), statusCode, reason);
            callback.succeed();
        } catch (Throwable failure) {
            callback.fail(failure);
        }
    }
}
