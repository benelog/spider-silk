package net.benelog.spidersilk;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The response headers a browser reads as instructions about how careful to be.
 *
 * <pre>{@code
 * app.securityHeaders();                          // the three defaults below
 *
 * app.securityHeaders(SecurityHeaders.defaults()
 *         .frameOptions("SAMEORIGIN")             // the app embeds itself
 *         .hsts(Duration.ofDays(365))             // once HTTPS is certain
 *         .contentSecurityPolicy("default-src 'self'"));
 * }</pre>
 *
 * <p>Three headers are on by default, because there is no application for which
 * their absence is the better answer:
 *
 * <ul>
 * <li>{@code X-Content-Type-Options: nosniff} — an upload served as
 *     {@code text/plain} is not to be re-read as a script.
 * <li>{@code X-Frame-Options: DENY} — nobody frames this page, which is what
 *     stops a clickjack.
 * <li>{@code Referrer-Policy: strict-origin-when-cross-origin} — a path with an
 *     id in it does not leak to whatever the user clicks through to.
 * </ul>
 *
 * <p>Two more are off until asked for, because either could break a working site
 * and only the application knows: {@link #hsts(Duration)}, which a browser
 * remembers for as long as it was told to, and
 * {@link #contentSecurityPolicy(String)}, which no default can guess.
 *
 * <p>Registered through {@link App#securityHeaders(SecurityHeaders)}, not as a
 * filter. A 404 is a page a browser renders like any other, and no
 * {@link AfterFilter} runs for one.
 *
 * <p>A response that set one of these headers itself keeps its own value: a
 * single page that has to be framed says so on the response, without turning the
 * header off everywhere.
 */
public final class SecurityHeaders {

    private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private static final String FRAME_OPTIONS = "X-Frame-Options";
    private static final String REFERRER_POLICY = "Referrer-Policy";
    private static final String HSTS = "Strict-Transport-Security";
    private static final String CSP = "Content-Security-Policy";
    private static final String PERMISSIONS_POLICY = "Permissions-Policy";

    /** In the order they go out, which is the order they were configured in. */
    private final Map<String, String> headers = new LinkedHashMap<>();

    private String hsts;

    private SecurityHeaders() {
        headers.put(CONTENT_TYPE_OPTIONS, "nosniff");
        headers.put(FRAME_OPTIONS, "DENY");
        headers.put(REFERRER_POLICY, "strict-origin-when-cross-origin");
    }

    /** The three headers described on this class. */
    public static SecurityHeaders defaults() {
        return new SecurityHeaders();
    }

    /**
     * Who may frame this application: {@code "DENY"} by default,
     * {@code "SAMEORIGIN"} for one that frames itself.
     */
    public SecurityHeaders frameOptions(String value) {
        return set(FRAME_OPTIONS, value);
    }

    /** How much of the URL travels to the next site. */
    public SecurityHeaders referrerPolicy(String value) {
        return set(REFERRER_POLICY, value);
    }

    /**
     * How long a browser should refuse to reach this site over plain HTTP,
     * subdomains included.
     *
     * <p>Off by default, and sent only on a request that already arrived over
     * HTTPS — a browser that learns this over HTTP has learned it from whoever
     * was in the way. Behind a TLS-terminating proxy that means the container
     * has to be told to trust {@code X-Forwarded-Proto}, or the header never
     * goes out at all.
     *
     * <p>It is also the one header here that cannot be taken back: a browser
     * that has been told a year holds that for a year, whatever the server says
     * next. Say a short duration first, and lengthen it once nothing on the site
     * needs HTTP.
     */
    public SecurityHeaders hsts(Duration maxAge) {
        return hsts(maxAge, true);
    }

    /** HSTS for this host alone, when a subdomain is not ready for it. */
    public SecurityHeaders hsts(Duration maxAge, boolean includeSubDomains) {
        Objects.requireNonNull(maxAge, "maxAge");
        this.hsts = "max-age=" + maxAge.toSeconds() + (includeSubDomains ? "; includeSubDomains" : "");
        return this;
    }

    /**
     * Where scripts, styles, and the rest may come from. No default: a policy
     * that does not match the application locks the application out of itself,
     * and only the application knows what it loads.
     */
    public SecurityHeaders contentSecurityPolicy(String policy) {
        return set(CSP, policy);
    }

    /** Which browser features the page may use — camera, geolocation, and so on. */
    public SecurityHeaders permissionsPolicy(String policy) {
        return set(PERMISSIONS_POLICY, policy);
    }

    /** Any other header to send on every response, at the same point as these. */
    public SecurityHeaders header(String name, String value) {
        return set(Objects.requireNonNull(name, "name"), value);
    }

    /**
     * Every response gains what it does not already say for itself. HSTS is the
     * exception that also depends on the request: it is only true over HTTPS.
     */
    WebResponse apply(WebResponse response, WebRequest request) {
        WebResponse answer = response;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            answer = addIfAbsent(answer, header.getKey(), header.getValue());
        }
        if (hsts != null && request.isSecure()) {
            answer = addIfAbsent(answer, HSTS, hsts);
        }
        return answer;
    }

    private static WebResponse addIfAbsent(WebResponse response, String name, String value) {
        return response.headerIgnoringCase(name) == null ? response.header(name, value) : response;
    }

    private SecurityHeaders set(String name, String value) {
        headers.put(name, Objects.requireNonNull(value, "value"));
        return this;
    }
}
