package net.benelog.spidersilk;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Files served off the classpath or off a directory, with the caching headers
 * that stop a browser re-downloading the stylesheet on every page load.
 *
 * <p>{@code classpath:/public} is served at the root without being asked for;
 * this is for a directory, a hosted path, or a cache policy of your own.
 *
 * <pre>{@code
 * app.staticFiles("/assets");                       // classpath:/assets/* at /*
 *
 * app.staticFiles(new StaticFiles("/public")
 *         .hostedPath("/assets")                    // classpath:/public/* at /assets/*
 *         .maxAge(Duration.ofDays(365)));           // for fingerprinted file names
 *
 * app.staticFiles(                                  // both, in the order given
 *         new StaticFiles("/public"),
 *         StaticFiles.directory(Path.of("/srv/uploads")).hostedPath("/uploads"));
 * }</pre>
 *
 * <p>Every response carries an {@code ETag} and {@code Last-Modified} derived
 * from the resource itself, so a conditional request comes back as a bodyless
 * 304. The default {@code Cache-Control} is {@code no-cache}, which means
 * "cache it, but check with me first" — correct for files whose name never
 * changes. {@link #maxAge(Duration)} is for the other kind, where the name
 * carries a content hash and the file at that name can never change.
 *
 * <p>A directory root is a path-traversal surface a classpath lookup does not
 * have, so {@link #directory(Path)} answers only for a regular file whose real
 * path — symbolic links resolved — lies under the root's real path. Anything
 * else is not a file this serves, and routing carries on as if it were absent.
 *
 * <p>{@link #precompressed()} answers with a {@code .br} or {@code .gz} sibling
 * of the file when the client will take that encoding, which is the only way
 * core answers brotli at all — the JDK ships no encoder — and the only way an
 * asset is not deflated again on every request that asks for it.
 */
public final class StaticFiles {

    /** The classpath root an {@link App} serves files from by default. */
    public static final String DEFAULT_ROOT = "/public";

    private static final String REVALIDATE = "no-cache";

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    /**
     * The encodings a sibling can carry, in the order they are preferred: brotli
     * first, being the smaller of the two and the one nothing else can produce.
     * The name on the left is the {@code Accept-Encoding} token, the one on the
     * right the file extension — {@code gzip} and {@code .gz} are not the same
     * spelling.
     */
    private static final List<Encoding> ENCODINGS =
            List.of(new Encoding("br", ".br"), new Encoding("gzip", ".gz"));

    private final Source source;
    private String hostedPath = "";
    private String cacheControl = REVALIDATE;
    private boolean precompressed;

    /** @param classpathRoot the classpath directory to serve, e.g. "/public" */
    public StaticFiles(String classpathRoot) {
        this(new ClasspathSource(withoutTrailingSlash(
                Objects.requireNonNull(classpathRoot, "classpathRoot"))));
    }

    private StaticFiles(Source source) {
        this.source = source;
    }

    /**
     * Files served from a directory on disk rather than the classpath: an
     * upload directory, a volume mounted beside the jar, whatever a separate
     * build writes into.
     *
     * <p>The root is read per request, not at construction, so a volume that is
     * mounted after the application starts needs no restart, and one that is
     * never mounted answers 404 rather than failing to boot.
     *
     * @param root the directory to serve
     */
    public static StaticFiles directory(Path root) {
        return new StaticFiles(new DirectorySource(Objects.requireNonNull(root, "root")));
    }

    /** The URL prefix the files appear under. The default, "/", is the root. */
    public StaticFiles hostedPath(String hostedPath) {
        this.hostedPath = withoutTrailingSlash(Objects.requireNonNull(hostedPath, "hostedPath"));
        return this;
    }

    /**
     * How long a client may reuse a file without asking. Only safe when the file
     * name changes whenever the content does.
     */
    public StaticFiles maxAge(Duration maxAge) {
        this.cacheControl = "public, max-age=" + maxAge.toSeconds();
        return this;
    }

    /** The raw Cache-Control header, when neither default fits. */
    public StaticFiles cacheControl(String cacheControl) {
        this.cacheControl = Objects.requireNonNull(cacheControl, "cacheControl");
        return this;
    }

    /**
     * Answers with the {@code app.css.br} or {@code app.css.gz} a build left
     * beside {@code app.css}, whenever the request will take that encoding.
     * Brotli wins where both exist and the client takes both. The answer
     * describes the original either way: its {@code Content-Type}, since a
     * {@code .gz} extension is the encoding and not the type, and its
     * validators, so a browser revalidating across encodings keeps its 304.
     *
     * <p>A sibling older than the file it sits next to is a build that did not
     * rerun, and is passed over rather than served as content that no longer
     * exists. Every answer from this root then carries
     * {@code Vary: Accept-Encoding}, sibling found or not, so a shared cache
     * never hands encoded bytes to a client that cannot read them.
     */
    public StaticFiles precompressed() {
        this.precompressed = true;
        return this;
    }

    /**
     * The response for the file this path names, or null when it names none and
     * routing should carry on. The body is a stream rather than a byte array, so
     * a large file never lands in memory whole.
     */
    WebResponse resolve(String path, HttpServletRequest req) throws IOException {
        String relative = relativePath(path);
        if (relative == null) {
            return null;
        }
        Resource resource = source.find(relative);
        if (resource == null) {
            return null;
        }

        long lastModified = resource.lastModified();
        long length = resource.length();
        Encoded encoded = precompressed ? sibling(relative, lastModified, req) : null;

        WebResponse response = WebResponse.empty().header("Cache-Control", cacheControl);
        if (precompressed) {
            response = response.vary("Accept-Encoding");
        }
        if (lastModified > 0) {
            String etag = etag(lastModified, length);
            response = response.header("ETag", encoded == null ? etag : "W/" + etag)
                    .header("Last-Modified", httpDate(lastModified));
            if (isUnchanged(req, etag, lastModified)) {
                resource.discard();
                if (encoded != null) {
                    encoded.resource().discard();
                }
                return response.status(HttpStatus.NOT_MODIFIED);
            }
        }

        Resource body = encoded == null ? resource : encoded.resource();
        if (encoded != null) {
            resource.discard();
            response = response.header("Content-Encoding", encoded.encoding());
        }
        long bodyLength = body.length();
        if ("HEAD".equals(req.getMethod())) {
            // The length below is the whole answer, so the body is not written and
            // nothing else will release what reading the metadata opened. The
            // writer stays in place regardless: compression rewrites the answer
            // after this point, and a discarded resource opens again if it runs.
            body.discard();
        }
        response = response
                .body(new WebResponse.Stream(out -> {
                    try (InputStream in = body.open()) {
                        in.transferTo(out);
                    }
                }))
                .contentType(ContentTypes.byPath(relative));
        return bodyLength >= 0
                ? response.header("Content-Length", Long.toString(bodyLength))
                : response;
    }

    /**
     * The pre-compressed file sitting next to this one that the request will
     * take, or null when the build left none, the client reads none, or the one
     * that is there is older than the file it claims to be a copy of.
     */
    private Encoded sibling(String relative, long lastModified, HttpServletRequest req)
            throws IOException {
        String accepted = req.getHeader("Accept-Encoding");
        for (Encoding encoding : ENCODINGS) {
            if (!AcceptHeader.accepts(accepted, encoding.token())) {
                continue;
            }
            Resource candidate = source.find(relative + encoding.extension());
            if (candidate == null) {
                continue;
            }
            if (isStale(candidate, lastModified)) {
                candidate.discard();
                continue;
            }
            return new Encoded(encoding.token(), candidate);
        }
        return null;
    }

    /**
     * Whether the sibling is older than the file it sits next to, which is a
     * build that compressed an earlier version and did not rerun. A time neither
     * of them reports is no evidence of staleness, so the sibling still answers.
     */
    private boolean isStale(Resource sibling, long lastModified) {
        long siblingModified = sibling.lastModified();
        return lastModified > 0 && siblingModified > 0 && siblingModified < lastModified;
    }

    /**
     * The IMF-fixdate HTTP wants: a two-digit day, English month and day names,
     * and GMT. {@code RFC_1123_DATE_TIME} writes a single-digit day, which a
     * client is not obliged to parse back.
     */
    private static String httpDate(long epochMillis) {
        return HTTP_DATE.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC));
    }

    /**
     * The path under {@link #hostedPath} a request asks for, or null when the
     * request is not for this directory at all.
     */
    private String relativePath(String path) {
        if (path.contains("..") || !path.startsWith(hostedPath)) {
            return null;
        }
        String relative = path.substring(hostedPath.length());
        if (relative.isEmpty() || !relative.startsWith("/") || relative.endsWith("/")) {
            return null;
        }
        return relative;
    }

    /**
     * Content changes imply a new modification time or a new length, and the
     * file is immutable within a deployment, so the two together identify it.
     */
    private String etag(long lastModified, long length) {
        return "\"" + Long.toHexString(lastModified) + "-" + Long.toHexString(length) + "\"";
    }

    private boolean isUnchanged(HttpServletRequest req, String etag, long lastModified) {
        String ifNoneMatch = req.getHeader("If-None-Match");
        if (ifNoneMatch != null) {
            for (String candidate : ifNoneMatch.split(",", -1)) {
                String trimmed = candidate.trim();
                if (trimmed.equals("*") || trimmed.equals(etag) || trimmed.equals("W/" + etag)) {
                    return true;
                }
            }
            return false;
        }
        long ifModifiedSince = ifModifiedSince(req);
        return ifModifiedSince >= 0 && lastModified / 1000 * 1000 <= ifModifiedSince;
    }

    private long ifModifiedSince(HttpServletRequest req) {
        try {
            return req.getDateHeader("If-Modified-Since");
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static String withoutTrailingSlash(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (!trimmed.isEmpty() && !trimmed.startsWith("/")) {
            throw new IllegalArgumentException("Path must start with \"/\": " + path);
        }
        return trimmed;
    }

    /** One encoding a sibling can carry: its header token and its extension. */
    private record Encoding(String token, String extension) {
    }

    /** The sibling that answers, and the encoding to announce it under. */
    private record Encoded(String encoding, Resource resource) {
    }

    /** Where the bytes come from: a classpath root or a directory. */
    private interface Source {

        /**
         * The file the given path — always starting with "/" — names under this
         * root, or null when the root holds no such file.
         */
        Resource find(String relative) throws IOException;
    }

    /**
     * One file a {@link Source} found: the validators it can be identified by,
     * and a way to read it once.
     */
    private interface Resource {

        long lastModified();

        long length();

        InputStream open() throws IOException;

        /** Releases what reading the metadata opened, when the body is not sent. */
        default void discard() {
        }
    }

    private record ClasspathSource(String root) implements Source {

        @Override
        public Resource find(String relative) throws IOException {
            URL url = StaticFiles.class.getResource(root + relative);
            if (url == null || isDirectory(url)) {
                return null;
            }
            return new UrlResource(url);
        }

        /** An exploded classpath hands back directories too; a listing is not a file. */
        private boolean isDirectory(URL url) {
            if (!"file".equals(url.getProtocol())) {
                return false;
            }
            try {
                return Files.isDirectory(Path.of(url.toURI()));
            } catch (URISyntaxException e) {
                return false;
            }
        }
    }

    /**
     * A classpath resource, read through the connection that reading its
     * metadata already opened. Asking a {@link URLConnection} for the
     * modification time or the length connects it, and what that connects stays
     * open until the body is read — so a resource whose body is not sent has to
     * be discarded, and one that is discarded and then read after all opens the
     * URL again rather than handing back a stream that is closed.
     */
    private static final class UrlResource implements Resource {

        private final URL url;
        private final URLConnection connection;
        private boolean discarded;

        UrlResource(URL url) throws IOException {
            this.url = url;
            this.connection = url.openConnection();
        }

        @Override
        public long lastModified() {
            return connection.getLastModified();
        }

        @Override
        public long length() {
            return connection.getContentLengthLong();
        }

        @Override
        public InputStream open() throws IOException {
            return discarded ? url.openStream() : connection.getInputStream();
        }

        @Override
        public void discard() {
            if (discarded) {
                return;
            }
            discarded = true;
            try (InputStream ignored = connection.getInputStream()) {
                // Opened only to be closed, which releases what the connection holds.
            } catch (IOException e) {
                // Nothing was opened, so there is nothing to release.
            }
        }
    }

    /**
     * A directory root, which unlike the classpath can be walked out of. The
     * guard is the real path: the file the request resolves to, with every
     * symbolic link followed, has to still lie under the root's own real path.
     */
    private record DirectorySource(Path root) implements Source {

        @Override
        public Resource find(String relative) {
            Path candidate;
            try {
                candidate = root.resolve(relative.substring(1));
            } catch (InvalidPathException e) {
                return null;
            }
            try {
                Path real = candidate.toRealPath();
                if (!real.startsWith(root.toRealPath())) {
                    return null;
                }
                BasicFileAttributes attributes =
                        Files.readAttributes(real, BasicFileAttributes.class);
                return attributes.isRegularFile()
                        ? new FileResource(real, attributes)
                        : null;
            } catch (IOException e) {
                // No such file, an unreadable one, or a root that is not mounted:
                // all of them mean this root does not answer for the path.
                return null;
            }
        }
    }

    private record FileResource(Path path, BasicFileAttributes attributes) implements Resource {

        @Override
        public long lastModified() {
            return attributes.lastModifiedTime().toMillis();
        }

        @Override
        public long length() {
            return attributes.size();
        }

        @Override
        public InputStream open() throws IOException {
            return Files.newInputStream(path);
        }
    }
}
