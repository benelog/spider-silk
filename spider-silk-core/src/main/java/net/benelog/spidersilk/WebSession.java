package net.benelog.spidersilk;

import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.jspecify.annotations.Nullable;

/**
 * The session of the request it came from, reached through
 * {@link WebRequest#session()}: every session read and write in one place.
 *
 * <pre>{@code
 * req.session().set("user", user);                    // signing in
 * User user = req.session().get("user", User.class);  // null when signed out
 * req.session().invalidate();                         // signing out
 * }</pre>
 *
 * <p>Holding one starts no session. Only {@link #set} creates one, so a read,
 * a removal, or an invalidation on a visitor who never had a session leaves
 * them without one.
 */
public final class WebSession {

    private final HttpServletRequest req;

    WebSession(HttpServletRequest req) {
        this.req = req;
    }

    /**
     * An attribute, or null when there is no session or no such attribute. The
     * type parameter is the caller's cast, so that
     * {@code User user = req.session().get("user")} reads as one line; a value of
     * another type fails at that assignment, as it would with the cast written out.
     *
     * <p>{@link #get(String, Class)} names the type instead, and fails at the
     * read rather than at the assignment.
     */
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    public <T> @Nullable T get(String key) {
        HttpSession session = req.getSession(false);
        return session == null ? null : (T) session.getAttribute(key);
    }

    /**
     * An attribute of a named type, or null when there is no session or no such
     * attribute.
     *
     * <p>The cast is {@link Class#cast}, so a value of another type fails on this
     * line, naming the key and both types, rather than on the assignment several
     * lines away that {@link #get(String)} fails on. The type is written at the
     * call site, so this is not reflection in the sense this framework avoids.
     *
     * <p>A value of the wrong type is a mismatch between the line that wrote the
     * session and the line that reads it, both of them the application's own, so
     * it is an {@link IllegalStateException} and a 500 — the same answer
     * {@link WebRequest#pathParam(String)} gives an undeclared variable, and not
     * the 400 that a caller's bad input earns.
     */
    public <T> @Nullable T get(String key, Class<T> type) {
        Objects.requireNonNull(type,
                "type: get(key, type) reads; removing an attribute is remove(key)");
        HttpSession session = req.getSession(false);
        Object value = session == null ? null : session.getAttribute(key);
        if (value != null && !type.isInstance(value)) {
            throw new IllegalStateException("Session attribute %s is a %s, not a %s"
                    .formatted(key, value.getClass().getName(), type.getName()));
        }
        return type.cast(value);
    }

    /**
     * Stores an attribute, creating the session if there is none yet.
     *
     * <p>A null value removes the attribute, which is what
     * {@link HttpSession#setAttribute} does with one;
     * {@link WebRequest#flash(String, String)} follows the same rule.
     * {@link #remove(String)} says the same thing by name, and does not create a
     * session to remove from.
     */
    public void set(String key, @Nullable Object value) {
        req.getSession(true).setAttribute(key, value);
    }

    /** Removes an attribute. Does nothing, and creates no session, when there is none. */
    public void remove(String key) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.removeAttribute(key);
        }
    }

    /**
     * Ends the session, so that everything in it is gone and the next request
     * starts a new one. What logging out is.
     *
     * <p>Does nothing when there is no session, since a visitor who was never
     * signed in has nothing to end. The request keeps working afterwards, but
     * reading an attribute answers null and writing one starts a session again,
     * so a handler invalidates and then returns.
     */
    public void invalidate() {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
