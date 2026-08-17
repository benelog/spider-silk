package spidersilk.test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

/**
 * The request a {@link TestRequest} hands to {@link spidersilk.WebRequest}.
 *
 * <p>Everything {@code WebRequest} reads is answered the way a container would;
 * the rest of the interface throws. The list of what it reads is short and does
 * not grow by accident — a method added there and missing here fails loudly on
 * the first test that reaches it, rather than returning a quiet null.
 */
final class StubServletRequest implements HttpServletRequest {

    private final String method;
    private final String path;
    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> queryParams;
    private final Map<String, List<String>> formParams;
    private final List<Cookie> cookies;
    private final Map<String, Part> parts;
    private final boolean multipart;
    private final String body;

    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private HttpSession session;

    StubServletRequest(String method, String path, Map<String, List<String>> headers,
            Map<String, List<String>> queryParams, Map<String, List<String>> formParams,
            List<Cookie> cookies, Map<String, Part> parts, boolean multipart, String body,
            HttpSession session) {
        this.method = method;
        this.path = path;
        this.headers = headers;
        this.queryParams = queryParams;
        this.formParams = formParams;
        this.cookies = cookies;
        this.parts = parts;
        this.multipart = multipart;
        this.body = body;
        this.session = session;
    }

    // ---- What the request is ----

    @Override
    public String getMethod() {
        return method;
    }

    /**
     * Empty, with {@link #getPathInfo()} carrying the whole path — the split a
     * servlet mapped at {@code "/*"} sees, which is how {@code AppServlet} runs.
     */
    @Override
    public String getServletPath() {
        return "";
    }

    @Override
    public String getPathInfo() {
        return path;
    }

    @Override
    public String getContextPath() {
        return "";
    }

    @Override
    public String getRequestURI() {
        return path;
    }

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer url = new StringBuffer("http://localhost").append(path);
        return url;
    }

    // ---- Headers ----

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(headers.getOrDefault(name, List.of()));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(headers.keySet());
    }

    @Override
    public int getIntHeader(String name) {
        String value = getHeader(name);
        return value == null ? -1 : Integer.parseInt(value);
    }

    @Override
    public long getDateHeader(String name) {
        String value = getHeader(name);
        if (value == null) {
            return -1;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Not a date header: " + value, e);
        }
    }

    @Override
    public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override
    public String getCharacterEncoding() {
        return StandardCharsets.UTF_8.name();
    }

    @Override
    public void setCharacterEncoding(String encoding) throws UnsupportedEncodingException {
        if (!StandardCharsets.UTF_8.name().equalsIgnoreCase(encoding)) {
            throw new UnsupportedEncodingException("This request is UTF-8: " + encoding);
        }
    }

    // ---- Parameters ----

    /**
     * Query values first, then form values — the order a container merges them
     * in, and what lets {@code WebRequest.formParams} subtract one from the
     * other.
     */
    @Override
    public String[] getParameterValues(String name) {
        List<String> merged = new ArrayList<>(queryParams.getOrDefault(name, List.of()));
        merged.addAll(formParams.getOrDefault(name, List.of()));
        return merged.isEmpty() ? null : merged.toArray(new String[0]);
    }

    @Override
    public String getParameter(String name) {
        String[] values = getParameterValues(name);
        return values == null ? null : values[0];
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameterNames());
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> all = new LinkedHashMap<>();
        for (String name : parameterNames()) {
            all.put(name, getParameterValues(name));
        }
        return Map.copyOf(all);
    }

    private Set<String> parameterNames() {
        Set<String> names = new LinkedHashSet<>(queryParams.keySet());
        names.addAll(formParams.keySet());
        return names;
    }

    @Override
    public String getQueryString() {
        if (queryParams.isEmpty()) {
            return null;
        }
        StringJoiner query = new StringJoiner("&");
        queryParams.forEach((name, values) ->
                values.forEach(value -> query.add(TestRequest.encode(name)
                        + "=" + TestRequest.encode(value))));
        return query.toString();
    }

    // ---- Body ----

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(bodyBytes()), StandardCharsets.UTF_8));
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream bytes = new ByteArrayInputStream(bodyBytes());
        return new ServletInputStream() {
            @Override
            public int read() {
                return bytes.read();
            }

            @Override
            public boolean isFinished() {
                return bytes.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw notSupported("Asynchronous reads");
            }
        };
    }

    private byte[] bodyBytes() {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int getContentLength() {
        return (int) getContentLengthLong();
    }

    @Override
    public long getContentLengthLong() {
        return body.isEmpty() ? -1 : bodyBytes().length;
    }

    // ---- Multipart ----

    /**
     * A request with no parts is not a multipart request, and a container says so
     * by throwing — which {@code WebRequest.file} turns into a 400. A part that
     * was never added is a null, the other 400.
     */
    @Override
    public Part getPart(String name) throws ServletException {
        requireMultipart();
        return parts.get(name);
    }

    @Override
    public Collection<Part> getParts() throws ServletException {
        requireMultipart();
        return List.copyOf(parts.values());
    }

    private void requireMultipart() throws ServletException {
        if (!multipart) {
            throw new ServletException("Not a multipart request");
        }
    }

    // ---- Cookies ----

    /** Null rather than an empty array when none were sent, as a container does. */
    @Override
    public Cookie[] getCookies() {
        return cookies.isEmpty() ? null : cookies.toArray(new Cookie[0]);
    }

    // ---- Session ----

    @Override
    public HttpSession getSession(boolean create) {
        if (session == null && create) {
            session = new StubSession();
        }
        return session;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    // ---- Attributes ----

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(new ArrayList<>(attributes.keySet()));
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    // ---- Connection details, answered plainly ----

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public String getScheme() {
        return "http";
    }

    @Override
    public String getServerName() {
        return "localhost";
    }

    @Override
    public int getServerPort() {
        return 80;
    }

    @Override
    public String getRemoteAddr() {
        return "127.0.0.1";
    }

    @Override
    public String getRemoteHost() {
        return "localhost";
    }

    @Override
    public int getRemotePort() {
        return 0;
    }

    @Override
    public String getLocalName() {
        return "localhost";
    }

    @Override
    public String getLocalAddr() {
        return "127.0.0.1";
    }

    @Override
    public int getLocalPort() {
        return 80;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public Locale getLocale() {
        return Locale.getDefault();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return Collections.enumeration(List.of(getLocale()));
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    @Override
    public String getRequestId() {
        return "test-request";
    }

    @Override
    public String getProtocolRequestId() {
        return "";
    }

    // ---- Everything a handler under test has no business reaching ----

    @Override
    public ServletContext getServletContext() {
        throw notSupported("A servlet context");
    }

    @Override
    public ServletConnection getServletConnection() {
        throw notSupported("A servlet connection");
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        throw notSupported("Request dispatch");
    }

    @Override
    public AsyncContext startAsync() {
        throw notSupported("Asynchronous dispatch");
    }

    @Override
    public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
        throw notSupported("Asynchronous dispatch");
    }

    @Override
    public boolean isAsyncStarted() {
        return false;
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw notSupported("Asynchronous dispatch");
    }

    @Override
    public String getAuthType() {
        return null;
    }

    @Override
    public String getRemoteUser() {
        return null;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public boolean authenticate(HttpServletResponse response) {
        throw notSupported("Container authentication");
    }

    @Override
    public void login(String username, String password) {
        throw notSupported("Container authentication");
    }

    @Override
    public void logout() {
        throw notSupported("Container authentication");
    }

    @Override
    public String getRequestedSessionId() {
        return null;
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return session != null;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    @Override
    public String changeSessionId() {
        throw notSupported("Changing the session id");
    }

    @Override
    public String getPathTranslated() {
        return null;
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
        throw notSupported("Protocol upgrade");
    }

    private static UnsupportedOperationException notSupported(String what) {
        return new UnsupportedOperationException(
                what + " is not available on a TestRequest. Use WebTest for a running server.");
    }

    /** The session a request carries when the builder was given attributes for one. */
    static final class StubSession implements HttpSession {

        private final Map<String, Object> attributes = new HashMap<>();
        private int maxInactiveInterval = 1800;
        private boolean valid = true;

        @Override
        public Object getAttribute(String name) {
            requireValid();
            return attributes.get(name);
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            requireValid();
            return Collections.enumeration(new ArrayList<>(attributes.keySet()));
        }

        @Override
        public void setAttribute(String name, Object value) {
            requireValid();
            if (value == null) {
                attributes.remove(name);
            } else {
                attributes.put(name, value);
            }
        }

        @Override
        public void removeAttribute(String name) {
            requireValid();
            attributes.remove(name);
        }

        @Override
        public void invalidate() {
            requireValid();
            attributes.clear();
            valid = false;
        }

        private void requireValid() {
            if (!valid) {
                throw new IllegalStateException("The session has been invalidated");
            }
        }

        @Override
        public String getId() {
            return "test-session";
        }

        @Override
        public long getCreationTime() {
            return 0L;
        }

        @Override
        public long getLastAccessedTime() {
            return 0L;
        }

        @Override
        public boolean isNew() {
            return true;
        }

        @Override
        public int getMaxInactiveInterval() {
            return maxInactiveInterval;
        }

        @Override
        public void setMaxInactiveInterval(int interval) {
            this.maxInactiveInterval = interval;
        }

        @Override
        public ServletContext getServletContext() {
            throw notSupported("A servlet context");
        }
    }
}
