package spidersilk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;

import spidersilk.json.Json;

/**
 * A context wrapping the request and the response.
 * Path variables, parameters, session, flash, and response helpers in one place.
 * Type conversion happens only through explicit methods (pathParamLong etc.).
 * No reflection.
 */
public final class WebContext {

    static final String FLASH_ATTRIBUTE = "spidersilk.flash";

    private final App app;
    private final HttpServletRequest req;
    private final HttpServletResponse res;
    private final Map<String, String> pathParams;

    private boolean bodyWritten;
    private String errorMessage;

    /** Public so tests can build a Context and call handler methods directly. */
    public WebContext(App app, HttpServletRequest req, HttpServletResponse res,
                   Map<String, String> pathParams) {
        this.app = app;
        this.req = req;
        this.res = res;
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
    public HttpServletRequest req() {
        return req;
    }

    public HttpServletResponse res() {
        return res;
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

    // ---- Response ----

    public WebContext status(int code) {
        res.setStatus(code);
        return this;
    }

    public WebContext header(String name, String value) {
        res.setHeader(name, value);
        return this;
    }

    /** Sets Content-Disposition so the response downloads as a file. */
    public WebContext attachment(String filename) {
        res.setHeader("Content-Disposition", "attachment; filename=\"%s\"".formatted(filename));
        return this;
    }

    public void redirect(String location) {
        bodyWritten = true;
        try {
            res.sendRedirect(location);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void html(String content) {
        write("text/html; charset=UTF-8", content);
    }

    public void text(String content) {
        write("text/plain; charset=UTF-8", content);
    }

    public void json(Json.JsonValue value) {
        json(value.toJson());
    }

    public void json(String rawJson) {
        write("application/json", rawJson);
    }

    public void bytes(byte[] data, String contentType) {
        bodyWritten = true;
        res.setContentType(contentType);
        try {
            res.getOutputStream().write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Renders a template with the engine set via {@link App#templates(TemplateRenderer)}. */
    public void render(String template, Map<String, Object> model) {
        if (app.templates == null) {
            throw new IllegalStateException(
                    "No template engine configured. Call App.templates(...).");
        }
        bodyWritten = true;
        res.setContentType("text/html; charset=UTF-8");
        try {
            app.templates.render(template, model, res.getWriter());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- Errors ----

    /**
     * Inside an {@link App#error(int, Handler)} handler, the plain-text message
     * the framework would have written. Null when the status came from a handler
     * rather than from the router or an {@link HttpException}.
     */
    public String errorMessage() {
        return errorMessage;
    }

    void errorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Whether a response body was produced through this context. Writing
     * straight to {@link #res()} bypasses the flag, and therefore bypasses
     * {@link App#error(int, Handler)}.
     */
    boolean bodyWritten() {
        return bodyWritten;
    }

    private void write(String contentType, String content) {
        bodyWritten = true;
        res.setContentType(contentType);
        try {
            res.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
