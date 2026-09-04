package net.benelog.spidersilk;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which other origins may call this application, and what they may send.
 *
 * <pre>{@code
 * app.cors(Cors.allowOrigin("https://app.example.com")
 *         .forPath("/api/*")
 *         .allowCredentials()
 *         .maxAge(Duration.ofHours(1)));
 * }</pre>
 *
 * <p>Registered through {@link App#cors(Cors)}, not as a filter. A preflight is
 * an {@code OPTIONS} for which no handler is registered, and a cross-origin 404
 * still has to carry the headers or the browser reports the wrong failure —
 * neither reaches a {@link BeforeFilter}, which runs only once a route has
 * matched.
 *
 * <p>Two things happen. Every response to a request carrying an {@code Origin}
 * this allows gains {@code Access-Control-Allow-Origin}, and a preflight —
 * {@code OPTIONS} with {@code Access-Control-Request-Method} — is answered on
 * the spot with the methods the path's routes imply, which is the {@code Allow}
 * header the router already works out. Registering an {@code OPTIONS} route of
 * your own takes the preflight back: a path with a handler for the method is
 * answered by that handler, headers and all.
 *
 * <p>Nothing here decides whether a request is allowed to happen. CORS is a rule
 * the browser enforces on what a script may <em>read</em>; a caller that is not
 * a browser ignores all of it. Authentication and authorization stay a
 * {@link BeforeFilter}'s job.
 */
public final class Cors {

    /** Empty means any origin, which is the {@code *} case. */
    private final Set<String> origins;

    private PathPattern pattern = new PathPattern("/*");
    private List<String> methods = List.of();
    private List<String> headers;
    private List<String> exposed = List.of();
    private boolean credentials;
    private Duration maxAge;

    private Cors(Set<String> origins) {
        this.origins = origins;
    }

    /**
     * The origins allowed, spelled as a browser sends them: scheme, host, and
     * port, with no trailing slash — {@code "https://app.example.com"}.
     *
     * @throws IllegalArgumentException on {@code "*"}, which is {@link #anyOrigin()}
     */
    public static Cors allowOrigin(String... origins) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String origin : origins) {
            Objects.requireNonNull(origin, "origin");
            if (origin.equals("*")) {
                throw new IllegalArgumentException("Say Cors.anyOrigin() rather than \"*\"");
            }
            allowed.add(origin);
        }
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("Name at least one origin, or say Cors.anyOrigin()");
        }
        return new Cors(allowed);
    }

    /**
     * Any origin at all: {@code Access-Control-Allow-Origin: *}. Right for a
     * public read-only API, and incompatible with {@link #allowCredentials()} —
     * a wildcard plus cookies is what the specification forbids outright.
     */
    public static Cors anyOrigin() {
        return new Cors(Set.of());
    }

    /**
     * The paths this covers, {@code "/*"} by default. A trailing {@code *}
     * covers the prefix and everything under it, the same as a filter's path.
     */
    public Cors forPath(String path) {
        this.pattern = new PathPattern(Objects.requireNonNull(path, "path"));
        return this;
    }

    /**
     * The methods a preflight is answered with, in place of the ones the path's
     * routes imply. Naming them is for the case where the answer should not
     * follow the routing table.
     */
    public Cors allowMethods(String... methods) {
        this.methods = List.of(methods);
        return this;
    }

    /**
     * The request headers a preflight allows. Unset, whatever the preflight asks
     * for is allowed back, which is what a same-origin request would have been
     * able to send anyway.
     */
    public Cors allowHeaders(String... names) {
        this.headers = List.of(names);
        return this;
    }

    /**
     * Response headers a script may read. Without this a browser exposes only
     * the handful CORS calls safe, so a custom {@code X-Total-Count} is invisible
     * to the caller until it is named here.
     */
    public Cors exposeHeaders(String... names) {
        this.exposed = List.of(names);
        return this;
    }

    /**
     * Lets the browser send cookies and read {@code Set-Cookie} back.
     *
     * @throws IllegalStateException on {@link #anyOrigin()}, which cannot carry
     *         credentials — name the origins instead
     */
    public Cors allowCredentials() {
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "Credentials cannot be sent to any origin. Name the origins with "
                            + "Cors.allowOrigin(...).");
        }
        this.credentials = true;
        return this;
    }

    /** How long a browser may reuse a preflight answer. Unset, it decides. */
    public Cors maxAge(Duration maxAge) {
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
        return this;
    }

    /**
     * The headers every cross-origin answer carries. A request with no
     * {@code Origin} is same-origin and gains nothing; an {@code Origin} that is
     * not allowed also gains nothing, and the browser is the one that turns that
     * silence into a failure.
     */
    WebResponse apply(WebResponse response, WebRequest request, String[] segments) {
        String origin = request.header("Origin");
        if (origin == null || !covers(segments)) {
            return response;
        }
        String allowed = allowedOrigin(origin);
        if (allowed == null) {
            return response;
        }
        WebResponse answer = response.header("Access-Control-Allow-Origin", allowed);
        if (!origins.isEmpty()) {
            // The answer differs per origin, so a shared cache must key on it.
            answer = answer.vary("Origin");
        }
        if (credentials) {
            answer = answer.header("Access-Control-Allow-Credentials", "true");
        }
        if (!exposed.isEmpty()) {
            answer = answer.header("Access-Control-Expose-Headers", String.join(", ", exposed));
        }
        return answer;
    }

    /**
     * The framework's own {@code OPTIONS} answer, turned into a preflight answer
     * when that is what the request is. The {@code Allow} header it already
     * carries is the set of methods the path's routes imply, which is exactly
     * what {@code Access-Control-Allow-Methods} has to say.
     *
     * <p>Anything else — an {@code OPTIONS} with no {@code Origin}, or one from
     * an origin this does not allow — comes back unchanged, still a plain
     * {@code Allow} answer.
     */
    WebResponse preflight(WebResponse optionsAnswer, WebRequest request, String allow,
            String[] segments) {
        String origin = request.header("Origin");
        if (origin == null || request.header("Access-Control-Request-Method") == null
                || !covers(segments) || allowedOrigin(origin) == null) {
            return optionsAnswer;
        }
        WebResponse answer = optionsAnswer.header("Access-Control-Allow-Methods",
                methods.isEmpty() ? allow : String.join(", ", methods));
        if (headers != null) {
            if (!headers.isEmpty()) {
                answer = answer.header("Access-Control-Allow-Headers", String.join(", ", headers));
            }
        } else {
            String asked = request.header("Access-Control-Request-Headers");
            if (asked != null) {
                answer = answer.header("Access-Control-Allow-Headers", asked);
            }
            // Reflected, so the answer depends on what was asked for.
            answer = answer.vary("Access-Control-Request-Headers");
        }
        if (maxAge != null) {
            answer = answer.header("Access-Control-Max-Age", Long.toString(maxAge.toSeconds()));
        }
        return answer;
    }

    private boolean covers(String[] segments) {
        return pattern.match(segments) != null;
    }

    /** What to echo back, or null when this origin is not one of ours. */
    private String allowedOrigin(String origin) {
        if (origins.isEmpty()) {
            return "*";
        }
        return origins.contains(origin) ? origin : null;
    }
}
