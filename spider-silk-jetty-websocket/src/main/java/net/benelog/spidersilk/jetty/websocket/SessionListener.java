package net.benelog.spidersilk.jetty.websocket;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

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
 * <h2>One callback at a time</h2>
 *
 * <p>Jetty delivers messages one at a time, but not the end of a connection: an
 * idle timeout or a failed write calls {@code onWebSocketError} and
 * {@code onWebSocketClose} on whichever thread noticed it, while a slow
 * {@code onWebSocketText} may still be running. This class makes the
 * {@link WebSocketHandler} promise true regardless. Every callback runs under
 * one lock; a message callback waits for it, and an error or a close that finds
 * it taken is queued and run, in order, by the thread that releases it. An
 * error or a close never blocks Jetty's thread, and one raised from inside a
 * callback, by {@code session.close()} say, runs after that callback returns
 * rather than nested in it.
 *
 * <p>Public because Jetty reaches an endpoint's callbacks through
 * {@code MethodHandles.publicLookup()}, which a package-private class refuses.
 * Nothing constructs one but this module, and nothing here is API to call.
 */
public final class SessionListener extends Session.Listener.AbstractAutoDemanding {

    private static final System.Logger LOG = System.getLogger(SessionListener.class.getName());

    private final WebSocketHandler handler;
    private final ReentrantLock turn = new ReentrantLock();
    private final Queue<Runnable> deferred = new ConcurrentLinkedQueue<>();

    SessionListener(WebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void onWebSocketOpen(Session session) {
        super.onWebSocketOpen(session);
        inTurn(() -> handler.onOpen(session));
    }

    @Override
    public void onWebSocketText(String message) {
        inTurn(() -> handler.onText(getSession(), message));
    }

    @Override
    public void onWebSocketBinary(ByteBuffer payload, Callback callback) {
        inTurn(() -> {
            try {
                handler.onBinary(getSession(), payload);
                callback.succeed();
            } catch (Throwable failure) {
                callback.fail(failure);
            }
        });
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        whenFree(() -> {
            try {
                handler.onError(getSession(), cause);
            } catch (Throwable failure) {
                failure.addSuppressed(cause);
                LOG.log(System.Logger.Level.WARNING, "WebSocketHandler.onError threw", failure);
            }
        });
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        whenFree(() -> {
            try {
                handler.onClose(getSession(), statusCode, reason);
                callback.succeed();
            } catch (Throwable failure) {
                callback.fail(failure);
            }
        });
    }

    /**
     * Runs a message callback, waiting for one on another thread to finish. A
     * failure propagates to Jetty, which answers it with {@code onWebSocketError}.
     */
    private void inTurn(Runnable callback) {
        turn.lock();
        try {
            callback.run();
        } finally {
            turn.unlock();
            runDeferred();
        }
    }

    /**
     * Runs an error or a close now if no callback is running, or leaves it to
     * the thread whose callback is. Queued first, so it never overtakes one
     * queued before it.
     */
    private void whenFree(Runnable callback) {
        deferred.add(callback);
        runDeferred();
    }

    /**
     * Drains the queue while the lock is free. The emptiness check is repeated
     * after the lock is released, so a callback queued by a thread that found
     * the lock taken a moment earlier is never left behind.
     */
    private void runDeferred() {
        while (!turn.isHeldByCurrentThread() && !deferred.isEmpty() && turn.tryLock()) {
            try {
                Runnable next;
                while ((next = deferred.poll()) != null) {
                    next.run();
                }
            } finally {
                turn.unlock();
            }
        }
    }
}
