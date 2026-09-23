package net.benelog.spidersilk;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import org.jspecify.annotations.Nullable;

/**
 * Compresses the response when the client says it can read it.
 *
 * <pre>{@code
 * app.gzip();                                     // the defaults below
 *
 * app.gzip(Gzip.defaults().minBytes(4096));       // only what is worth the CPU
 * }</pre>
 *
 * <p>Registered through {@link App#gzip(Gzip)}, not as a filter: the stylesheet
 * a browser spends the most time downloading is a static file, and a static file
 * bypasses route filters. Compression runs after response filters.
 *
 * <p>Compression is a transform over the {@link WebResponse}, not a wrapper
 * around the servlet response. A body already in memory — {@link WebResponse.Text},
 * {@link WebResponse.Bytes} — is compressed there and then, so the length it
 * answers with is the length it sends. A {@link WebResponse.Streamed} is
 * compressed as it is written, which is what keeps a large file out of memory;
 * its size is unknown beforehand, so {@link #minBytes(int)} does not apply to
 * one and its {@code Content-Length} is dropped.
 *
 * <p>Server-Sent Events and {@link WebResponse.Raw} are never compressed: an SSE
 * stream buffered until it is worth deflating is an SSE stream that no longer
 * arrives, and a raw handler owns its own bytes by definition.
 *
 * <h2>Caching</h2>
 *
 * <p>Every compressible answer carries {@code Vary: Accept-Encoding}, whether it
 * ended up compressed or not, so a shared cache never hands gzipped bytes to a
 * client that cannot read them. A compressed answer's {@code ETag} — the one
 * {@link StaticFiles} derives from the file — is marked weak, because the
 * compressed and uncompressed bodies are the same file and not the same bytes.
 * A browser revalidating with the weak tag still gets its 304.
 */
public final class Gzip {

    /** Below this, the gzip header costs more than the compression saves. */
    public static final int DEFAULT_MIN_BYTES = 1024;

    /**
     * Content types worth compressing, matched as prefixes. Everything absent
     * here — JPEG, PNG, WebP, MP4, zip archives — is already compressed, and
     * deflating it again spends CPU to make it slightly larger.
     */
    public static final List<String> DEFAULT_TYPES = List.of(
            "text/",
            "application/json",
            "application/x-ndjson",
            "application/javascript",
            "application/xml",
            "application/xhtml+xml",
            "application/manifest+json",
            "image/svg+xml");

    private int minBytes = DEFAULT_MIN_BYTES;
    private List<String> types = lowerCased(DEFAULT_TYPES);

    private Gzip() {
    }

    /**
     * The copy {@link App#gzip(Gzip)} keeps, so a reference the caller holds on
     * to cannot retune an application that has already been handed this.
     */
    Gzip copy() {
        Gzip copy = new Gzip();
        copy.minBytes = minBytes;
        copy.types = types;
        return copy;
    }

    /** Compression as described on this class. */
    public static Gzip defaults() {
        return new Gzip();
    }

    /**
     * The smallest body worth compressing. Applies to a body already in memory;
     * a stream's length is not known until it has been written.
     */
    public Gzip minBytes(int minBytes) {
        if (minBytes < 0) {
            throw new IllegalArgumentException("minBytes cannot be negative: " + minBytes);
        }
        this.minBytes = minBytes;
        return this;
    }

    /**
     * The content types to compress, in place of {@link #DEFAULT_TYPES}. Each is
     * matched as a prefix of the response's {@code Content-Type}, so
     * {@code "text/"} covers HTML, CSS, and plain text alike. The match ignores
     * case, which a media type is defined to do.
     */
    public Gzip types(String... types) {
        this.types = lowerCased(List.of(types));
        return this;
    }

    /** The list a request is matched against: folded once, here, not per request. */
    private static List<String> lowerCased(List<String> types) {
        return types.stream().map(type -> type.toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * The response as it goes on the wire. Unchanged when there is nothing to
     * gain: a body that is not compressible, a client that did not ask, a body
     * too small to bother with, or one that came out no smaller.
     */
    WebResponse apply(WebResponse response, WebRequest request) {
        if (!isCompressible(response)) {
            return response;
        }
        // Set before deciding: the answer varies by Accept-Encoding either way,
        // and a cache that stored this one must not reuse it for the other.
        WebResponse varying = response.vary("Accept-Encoding");
        if (!acceptsGzip(request)) {
            return varying;
        }
        return switch (response.body()) {
            case WebResponse.Text text -> compressed(varying, text.content());
            case WebResponse.Bytes bytes -> compressed(varying, bytes.data());
            case WebResponse.Streamed stream -> compressed(varying, stream.writer());
            case WebResponse.Empty ignored -> varying;
            case WebResponse.Template ignored -> varying;
            case WebResponse.Sse ignored -> varying;
            case WebResponse.Raw ignored -> varying;
        };
    }

    /**
     * Whether this answer is one to compress at all: it has a body worth
     * compressing, of a type that compresses, and nothing has encoded it already.
     */
    private boolean isCompressible(WebResponse response) {
        if (response.status() == HttpStatus.NO_CONTENT
                || response.status() == HttpStatus.NOT_MODIFIED) {
            return false;
        }
        if (response.header("Content-Encoding") != null) {
            return false;
        }
        if (response.body() instanceof WebResponse.Sse
                || response.body() instanceof WebResponse.Raw
                || response.body() instanceof WebResponse.Empty) {
            return false;
        }
        return isCompressibleType(response.header("Content-Type"));
    }

    private boolean isCompressibleType(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        for (String candidate : types) {
            if (type.startsWith(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the client listed gzip as something it can read. {@code gzip;q=0}
     * is a client saying the opposite, and {@code *} is one saying anything goes.
     * The same reading of the same grammar {@link WebRequest#accepts} applies to
     * {@code Accept}, so both go through {@link AcceptHeader}.
     */
    private static boolean acceptsGzip(WebRequest request) {
        return AcceptHeader.accepts(request.header("Accept-Encoding"), "gzip");
    }

    /**
     * A text body, measured before it is encoded. A declined body stays the
     * {@link WebResponse.Text} it came in as, so the request logger sees the same
     * body with gzip on as with it off, and the writer encodes it once. Only a
     * body that reaches the threshold is encoded here; if that one then comes out
     * no smaller, the writer encodes it a second time, which is the rare case.
     */
    private WebResponse compressed(WebResponse response, String content) {
        if (!reachesUtf8Bytes(content, minBytes)) {
            return response;
        }
        // Text is written as UTF-8, so this is the byte count the writer would send.
        return compressed(response, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Whether {@code content} encodes to at least {@code threshold} bytes of
     * UTF-8, answered without allocating the bytes. A char encodes to at least
     * one byte and at most three (a surrogate pair is two chars and four bytes),
     * so most strings are decided by their length alone; the rest are counted
     * until the count reaches the threshold. An unpaired surrogate counts as the
     * one byte {@link String#getBytes} replaces it with.
     */
    static boolean reachesUtf8Bytes(String content, int threshold) {
        int length = content.length();
        if (length >= threshold) {
            return true;
        }
        if ((long) length * 3 < threshold) {
            return false;
        }
        long bytes = 0;
        for (int i = 0; i < length && bytes < threshold; i++) {
            char c = content.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < length && Character.isLowSurrogate(content.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                bytes += 1;
            } else {
                bytes += 3;
            }
        }
        return bytes >= threshold;
    }

    /** A body already in memory, compressed now so its length is known now. */
    private WebResponse compressed(WebResponse response, byte[] raw) {
        if (raw.length < minBytes) {
            return response;
        }
        byte[] zipped = deflate(raw);
        if (zipped.length >= raw.length) {
            return response;
        }
        return encoded(response.body(new WebResponse.Bytes(zipped)))
                .header("Content-Length", Integer.toString(zipped.length));
    }

    /**
     * A body written as it goes, compressed the same way. The length the
     * uncompressed body announced no longer describes what is sent, so it goes.
     */
    private WebResponse compressed(WebResponse response, StreamWriter writer) {
        return encoded(response.body(new WebResponse.Streamed(out -> {
            // Only a writer that returned has a body to end. close() writes the
            // trailer and flushes, which on a failed writer would commit what
            // looks like a whole, empty 200; abandon() ends the Deflater and
            // writes nothing, so the servlet can still answer 500, or let the
            // container abort a transfer that is already under way.
            Deflating zipped = new Deflating(new NonClosing(out));
            try {
                writer.write(zipped);
            } catch (Throwable failure) {
                zipped.abandon();
                throw failure;
            }
            zipped.close();
        }))).withoutHeader("Content-Length");
    }

    /** What every compressed answer says about itself. */
    private static WebResponse encoded(WebResponse response) {
        WebResponse encoded = response.header("Content-Encoding", "gzip");
        String etag = encoded.header("ETag");
        if (etag == null || etag.startsWith("W/")) {
            return encoded;
        }
        // Same representation, different bytes: that is what a weak tag means,
        // and StaticFiles accepts the weak form back on the next request.
        return encoded.header("ETag", "W/" + etag);
    }

    private static byte[] deflate(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, raw.length / 4));
        try (GZIPOutputStream zipped = new GZIPOutputStream(out)) {
            zipped.write(raw);
        } catch (IOException e) {
            // A ByteArrayOutputStream does not fail; the checked type is the API's.
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * A gzip stream that can be given up on. {@link #close()} finishes the body:
     * it writes the trailer, ends the Deflater, and flushes. {@link #abandon()}
     * ends the Deflater's native memory without writing another byte, which is
     * what a writer that failed halfway leaves behind.
     */
    private static final class Deflating extends GZIPOutputStream {

        Deflating(OutputStream out) throws IOException {
            super(out);
        }

        void abandon() {
            def.end();
        }
    }

    /**
     * The response stream, wrapped so that closing the gzip stream over it ends
     * the deflater without ending the response. {@link FilterOutputStream} passes
     * an array write on one byte at a time, which is the whole cost of streaming
     * a large file, so that one goes straight through.
     */
    private static final class NonClosing extends FilterOutputStream {

        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        /** Flushes what the deflater just wrote; the container closes the rest. */
        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
