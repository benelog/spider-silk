package net.benelog.spidersilk.json;

/**
 * What every JSON accessor and {@link Json#parse} throw: a missing key, a value of the wrong
 * type, or text that is not JSON.
 *
 * <p>It is an {@link IllegalArgumentException}, so a {@link JsonReader}
 * that throws that type for a rule of its own is rejected the same way, and
 * {@code req.bodyJson(reader)} turns both into a 400. It is also a type of
 * its own, so an application that maps {@code IllegalArgumentException} to
 * a status can map this one to another: a body that failed to parse is a
 * 400 whatever the application says a bad argument is.
 */
public final class JsonException extends IllegalArgumentException {

    JsonException(String message) {
        super(message);
    }
}
