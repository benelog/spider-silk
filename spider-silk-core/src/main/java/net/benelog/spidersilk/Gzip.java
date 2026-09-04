package net.benelog.spidersilk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

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
 * is answered before any filter runs.
 *
 * <p>Compression is a transform over the {@link WebResponse}, not a wrapper
 * around the servlet response. A body already in memory — {@link WebResponse.Text},
 * {@link WebResponse.Bytes} — is compressed there and then, so the length it
 * answers with is the length it sends. A {@link WebResponse.Stream} is
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
    private List<String> types = DEFAULT_TYPES;

    private Gzip() {
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
     * {@code "text/"} covers HTML, CSS, and plain text alike.
     */
    public Gzip types(String... types) {
        this.types = List.of(types);
        return this;
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
            case WebResponse.Text text ->
                    compressed(varying, text.content().getBytes(StandardCharsets.UTF_8));
            case WebResponse.Bytes bytes -> compressed(varying, bytes.data());
            case WebResponse.Stream stream -> compressed(varying, stream.writer());
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

    private boolean isCompressibleType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        for (String candidate : types) {
            if (type.startsWith(candidate.toLowerCase(Locale.ROOT))) {
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
        return encoded(response.body(new WebResponse.Stream(out -> {
            GZIPOutputStream zipped = new GZIPOutputStream(out);
            writer.write(zipped);
            zipped.finish();
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
}
