package spidersilk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;

import spidersilk.json.Json;
import spidersilk.json.JsonReader;

/**
 * The request side of a handler: what was asked for, and the session it was
 * asked in. The answer is the {@link WebResponse} the handler returns.
 *
 * <p>Reading is the whole of it, with one deliberate exception: the session.
 * {@link #sessionAttr(String, Object)} and {@link #flash(String, String)} write,
 * because a session outlives the response and cannot be a value returned from
 * one. Cookies are the other half of that split — the ones the client sent are
 * read here, the ones the server sets belong to {@link WebResponse}.
 *
 * <p>Type conversion happens only through explicit methods
 * ({@link #pathParamLong} and the like). No reflection.
 */
public final class WebRequest {

    static final String FLASH_ATTRIBUTE = "spidersilk.flash";

    private final HttpServletRequest req;
    private final Map<String, String> pathParams;

    private Map<String, List<String>> queryString;
    private String errorMessage;

    /** Public so a test can build a request and call a handler method directly. */
    public WebRequest(HttpServletRequest req, Map<String, String> pathParams) {
        this.req = req;
        this.pathParams = pathParams;
    }

    // ---- Request info ----

    public String method() {
        return req.getMethod();
    }

    public String path() {
        String path = req.getServletPath();
        if (req.getPathInfo() != null) {
            path = path + req.getPathInfo();
        }
        return path.isEmpty() ? "/" : path;
    }

    public String header(String name) {
        return req.getHeader(name);
    }

    /** Escape hatch when the raw request is needed. */
    public HttpServletRequest raw() {
        return req;
    }

    // ---- Path variables ----

    public String pathParam(String name) {
        String value = pathParams.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Path pattern has no such variable: {" + name + "}");
        }
        return value;
    }

    public long pathParamLong(String name) {
        String value = pathParam(name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new HttpException(400,
                    "Path variable {%s} is not a number: %s".formatted(name, value));
        }
    }

    public <E extends Enum<E>> E pathParamEnum(String name, Class<E> type) {
        String value = pathParam(name);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new HttpException(400,
                    "Invalid value for path variable {%s}: %s".formatted(name, value));
        }
    }

    // ---- Query string and form parameters ----

    /** A required parameter. Responds with 400 if missing. */
    public String param(String name) {
        String value = req.getParameter(name);
        if (value == null) {
            throw new HttpException(400, "Missing required parameter: " + name);
        }
        return value;
    }

    public String param(String name, String defaultValue) {
        String value = req.getParameter(name);
        return value != null ? value : defaultValue;
    }

    public long paramLong(String name) {
        String value = param(name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new HttpException(400,
                    "Parameter %s is not a number: %s".formatted(name, value));
        }
    }

    public boolean paramBoolean(String name, boolean defaultValue) {
        String value = req.getParameter(name);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    public <E extends Enum<E>> E paramEnum(String name, Class<E> type) {
        String value = param(name);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new HttpException(400,
                    "Invalid value for parameter %s: %s".formatted(name, value));
        }
    }

    /**
     * Every value of a repeated parameter, in the order the request sent them —
     * the shape a group of checkboxes or a multi-select arrives in. Empty when
     * the parameter is absent, since "none checked" is an answer, not an error.
     * {@link #param(String)} returns the first of these.
     */
    public List<String> params(String name) {
        String[] values = req.getParameterValues(name);
        return values == null ? List.of() : List.of(values);
    }

    /**
     * A parameter from the query string only, or null. The servlet API merges
     * the query string with a form body, so an {@code id} in the URL and an
     * {@code id} in the form both answer to {@link #param(String)}; this is the
     * way to say which one you meant.
     */
    public String queryParam(String name) {
        List<String> values = queryParams(name);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Every value of a repeated query-string parameter. */
    public List<String> queryParams(String name) {
        return queryString().getOrDefault(name, List.of());
    }

    /**
     * A parameter from the form body only, or null — the counterpart to
     * {@link #queryParam(String)}. Only present for a form-encoded body the
     * container parsed; a JSON body is read with {@link #body()}.
     */
    public String formParam(String name) {
        List<String> values = formParams(name);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Every value of a repeated form field. */
    public List<String> formParams(String name) {
        List<String> merged = params(name);
        List<String> fromQuery = queryParams(name);
        if (fromQuery.isEmpty()) {
            return merged;
        }
        // The container hands back query and form values in one list. Take out
        // one entry per query value: what is left came from the body.
        List<String> remaining = new ArrayList<>(merged);
        for (String value : fromQuery) {
            remaining.remove(value);
        }
        return List.copyOf(remaining);
    }

    private Map<String, List<String>> queryString() {
        if (queryString == null) {
            queryString = parseQueryString(req.getQueryString());
        }
        return queryString;
    }

    private static Map<String, List<String>> parseQueryString(String query) {
        if (query == null || query.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            parsed.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return parsed;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    // ---- Body ----

    public String body() {
        try {
            return req.getReader().lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses the body as JSON. Responds with 400 on invalid syntax. */
    public Json.JsonValue bodyJson() {
        try {
            return Json.parse(body());
        } catch (IllegalArgumentException e) {
            throw new HttpException(400, "Request body is not valid JSON: " + e.getMessage());
        }
    }

    /**
     * Parses the body as JSON and hands it to a hand-written reader. Responds
     * with 400 both on invalid syntax and on a body the reader rejects — a
     * missing key or a value of the wrong type — so a handler receives a whole
     * value or nothing at all, the same contract as {@link #pathParamLong}.
     */
    public <T> T bodyJson(JsonReader<T> reader) {
        Json.JsonValue json = bodyJson();
        try {
            return reader.read(json);
        } catch (IllegalArgumentException e) {
            throw new HttpException(400, "Request body was rejected: " + e.getMessage());
        }
    }

    /** A multipart upload. Responds with 400 if missing. */
    public UploadedFile file(String name) {
        try {
            Part part = req.getPart(name);
            if (part == null) {
                throw new HttpException(400, "Missing uploaded file: " + name);
            }
            return new UploadedFile(part);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ServletException e) {
            throw new HttpException(400, "Not a multipart request");
        }
    }

    // ---- Cookies the client sent ----

    /** A cookie the client sent, or null. Setting one is {@link WebResponse#cookie}. */
    public String cookie(String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** Every cookie the client sent. A repeated name keeps the first value. */
    public Map<String, String> cookies() {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return Map.of();
        }
        Map<String, String> byName = new LinkedHashMap<>();
        for (Cookie cookie : cookies) {
            byName.putIfAbsent(cookie.getName(), cookie.getValue());
        }
        return byName;
    }

    // ---- Session ----

    @SuppressWarnings("unchecked")
    public <T> T sessionAttr(String key) {
        HttpSession session = req.getSession(false);
        return session == null ? null : (T) session.getAttribute(key);
    }

    public void sessionAttr(String key, Object value) {
        req.getSession(true).setAttribute(key, value);
    }

    public void removeSessionAttr(String key) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.removeAttribute(key);
        }
    }

    // ---- Flash (visible exactly once, on the request after a redirect) ----

    public void flash(String key, String value) {
        HttpSession session = req.getSession(true);
        @SuppressWarnings("unchecked")
        Map<String, String> flash = (Map<String, String>) session.getAttribute(FLASH_ATTRIBUTE);
        if (flash == null) {
            flash = new HashMap<>();
            session.setAttribute(FLASH_ATTRIBUTE, flash);
        }
        flash.put(key, value);
    }

    /** A flash value left by the previous request, or null. */
    public String flashed(String key) {
        Object attribute = req.getAttribute(FLASH_ATTRIBUTE);
        if (attribute instanceof Map<?, ?> flash) {
            Object value = flash.get(key);
            return value == null ? null : value.toString();
        }
        return null;
    }

    // ---- Errors ----

    /**
     * Inside an {@link App#error(int, Handler)} handler, the plain-text message
     * the framework would have answered with. Null when the status came from a
     * handler rather than from the router or an {@link HttpException}.
     */
    public String errorMessage() {
        return errorMessage;
    }

    void errorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /** The same request, with the path variables a matched route resolved. */
    WebRequest withPathParams(Map<String, String> resolved) {
        WebRequest copy = new WebRequest(req, resolved);
        copy.errorMessage = errorMessage;
        return copy;
    }
}
