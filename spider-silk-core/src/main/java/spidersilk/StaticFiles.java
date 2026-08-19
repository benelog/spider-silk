package spidersilk;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Files served straight off the classpath, with the caching headers that stop a
 * browser re-downloading the stylesheet on every page load.
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
 * }</pre>
 *
 * <p>Every response carries an {@code ETag} and {@code Last-Modified} derived
 * from the resource itself, so a conditional request comes back as a bodyless
 * 304. The default {@code Cache-Control} is {@code no-cache}, which means
 * "cache it, but check with me first" — correct for files whose name never
 * changes. {@link #maxAge(Duration)} is for the other kind, where the name
 * carries a content hash and the file at that name can never change.
 */
public final class StaticFiles {

    /** The classpath root an {@link App} serves files from by default. */
    public static final String DEFAULT_ROOT = "/public";

    private static final String REVALIDATE = "no-cache";

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private final String classpathRoot;
    private String hostedPath = "";
    private String cacheControl = REVALIDATE;

    /** @param classpathRoot the classpath directory to serve, e.g. "/public" */
    public StaticFiles(String classpathRoot) {
        this.classpathRoot = withoutTrailingSlash(
                Objects.requireNonNull(classpathRoot, "classpathRoot"));
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
     * The response for the file this path names, or null when it names none and
     * routing should carry on. The body is a stream rather than a byte array, so
     * a large file never lands in memory whole.
     */
    WebResponse resolve(String path, HttpServletRequest req) throws IOException {
        String relative = relativePath(path);
        if (relative == null) {
            return null;
        }
        URL url = StaticFiles.class.getResource(classpathRoot + relative);
        if (url == null || isDirectory(url)) {
            return null;
        }

        URLConnection connection = url.openConnection();
        long lastModified = connection.getLastModified();
        long length = connection.getContentLengthLong();

        WebResponse response = WebResponse.empty().header("Cache-Control", cacheControl);
        if (lastModified > 0) {
            String etag = etag(lastModified, length);
            response = response.header("ETag", etag)
                    .header("Last-Modified", httpDate(lastModified));
            if (isUnchanged(req, etag, lastModified)) {
                discard(connection);
                return response.status(HttpStatus.NOT_MODIFIED);
            }
        }

        response = response
                .body(new WebResponse.Stream(out -> {
                    try (InputStream in = connection.getInputStream()) {
                        in.transferTo(out);
                    }
                }))
                .contentType(ContentTypes.byPath(relative));
        return length >= 0 ? response.header("Content-Length", Long.toString(length)) : response;
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
        // A bare directory is not a file, and neither is the hosted path itself.
        if (relative.isEmpty() || !relative.startsWith("/") || relative.endsWith("/")) {
            return null;
        }
        return relative;
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
            for (String candidate : ifNoneMatch.split(",")) {
                String trimmed = candidate.trim();
                if (trimmed.equals("*") || trimmed.equals(etag) || trimmed.equals("W/" + etag)) {
                    return true;
                }
            }
            // A validator was offered and did not match; the date is not consulted.
            return false;
        }
        long ifModifiedSince = ifModifiedSince(req);
        // Last-Modified goes out with second precision, so compare at that precision.
        return ifModifiedSince >= 0 && lastModified / 1000 * 1000 <= ifModifiedSince;
    }

    private long ifModifiedSince(HttpServletRequest req) {
        try {
            return req.getDateHeader("If-Modified-Since");
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** Closes what reading the metadata opened, since the body is not being sent. */
    private void discard(URLConnection connection) {
        try (InputStream ignored = connection.getInputStream()) {
            // opened only to be closed
        } catch (IOException e) {
            // nothing was sent; there is nothing to recover
        }
    }

    private static String withoutTrailingSlash(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (!trimmed.isEmpty() && !trimmed.startsWith("/")) {
            throw new IllegalArgumentException("Path must start with \"/\": " + path);
        }
        return trimmed;
    }
}
