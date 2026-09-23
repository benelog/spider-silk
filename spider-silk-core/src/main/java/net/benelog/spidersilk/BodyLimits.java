package net.benelog.spidersilk;

/**
 * How much of a request body the framework holds in memory at once.
 *
 * <pre>{@code
 * app.bodyLimits(BodyLimits.defaults().maxBytes(8 * 1024 * 1024));   // 8MB bodies
 *
 * app.bodyLimits(BodyLimits.unlimited());                            // bounded elsewhere
 * }</pre>
 *
 * <p>Two reads buffer, and each has its own limit. {@link WebRequest#body()} —
 * and {@link WebRequest#bodyJson()} over it — holds the whole body, so
 * {@link #maxBytes(int)} bounds the body. {@link WebRequest#bodyNdjson} holds
 * one line at a time, so {@link #maxNdjsonLineBytes(int)} bounds a line and
 * leaves the number of lines free. Both default to 1MB, and apply without
 * {@link App#bodyLimits(BodyLimits)} being called.
 *
 * <p>Both count bytes as they arrive, before any character is decoded: a
 * {@code Content-Length} header over the limit is refused without reading, and
 * a body that declares none, or declares less than it sends, is refused at the
 * first byte past the limit. A body over its limit answers
 * {@link HttpStatus#CONTENT_TOO_LARGE} through the ordinary error path, and a
 * request refused once stays refused: no later read hands the handler the part
 * of the body nobody checked.
 *
 * <p>{@link WebRequest#bodyStream()} and {@link WebRequest#bodyReader()} are
 * not limited, because a caller that reads a stream decides for itself how much
 * of it to keep. Form fields and multipart uploads are parsed by the servlet
 * container, under the container's own limits, and are not counted here.
 */
public final class BodyLimits {

    /** The default for both limits: 1MB. */
    public static final int DEFAULT_MAX_BYTES = 1024 * 1024;

    private int maxBytes = DEFAULT_MAX_BYTES;
    private int maxNdjsonLineBytes = DEFAULT_MAX_BYTES;

    private BodyLimits() {
    }

    /** Both limits at {@link #DEFAULT_MAX_BYTES}. */
    public static BodyLimits defaults() {
        return new BodyLimits();
    }

    /**
     * No limit short of what one Java array holds, for an application that sits
     * behind a proxy or a container already bounding the body.
     */
    public static BodyLimits unlimited() {
        return new BodyLimits().maxBytes(Integer.MAX_VALUE).maxNdjsonLineBytes(Integer.MAX_VALUE);
    }

    /** The copy {@link App#bodyLimits(BodyLimits)} keeps. */
    BodyLimits copy() {
        BodyLimits copy = new BodyLimits();
        copy.maxBytes = maxBytes;
        copy.maxNdjsonLineBytes = maxNdjsonLineBytes;
        return copy;
    }

    /**
     * The largest body {@link WebRequest#body()} reads, in bytes. A body of
     * exactly this many bytes is read.
     */
    public BodyLimits maxBytes(int maxBytes) {
        this.maxBytes = requirePositive("maxBytes", maxBytes);
        return this;
    }

    /**
     * The largest line {@link WebRequest#bodyNdjson} reads, in bytes, not
     * counting the line break. A line of exactly this many bytes is read.
     */
    public BodyLimits maxNdjsonLineBytes(int maxNdjsonLineBytes) {
        this.maxNdjsonLineBytes = requirePositive("maxNdjsonLineBytes", maxNdjsonLineBytes);
        return this;
    }

    int maxBytes() {
        return maxBytes;
    }

    int maxNdjsonLineBytes() {
        return maxNdjsonLineBytes;
    }

    private static int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
        return value;
    }
}
