package net.benelog.spidersilk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * What one {@link AppServlet} serves: the routes and the settings of an
 * {@link App}, copied when the servlet is initialized.
 *
 * <p>Nothing here changes after it is built, and nothing registered on the
 * {@code App} afterwards reaches it. The routing table is a copy of the
 * router, the lists and maps are unmodifiable copies, and the settings are
 * the copies {@code App} already made when they were registered. A request
 * thread therefore reads a table that cannot change underneath it, whatever
 * the application does with the {@code App} in the meantime.
 */
record Deployment(
        Router router,
        List<BeforeEntry> requestFilters,
        List<BeforeEntry> beforeFilters,
        List<AfterEntry> afterFilters,
        List<ResponseFilter> responseFilters,
        Map<Class<? extends Exception>, ExceptionHandler<? extends Exception>> exceptionHandlers,
        Map<HttpStatus, Handler> statusPages,
        List<StaticFiles> staticFiles,
        @Nullable RequestLogger requestLogger,
        @Nullable Cors cors,
        @Nullable Gzip gzip,
        @Nullable SecurityHeaders securityHeaders,
        BodyLimits bodyLimits) {

    Deployment {
        requestFilters = List.copyOf(requestFilters);
        beforeFilters = List.copyOf(beforeFilters);
        afterFilters = List.copyOf(afterFilters);
        responseFilters = List.copyOf(responseFilters);
        // Insertion order is kept, which Map.copyOf would not promise.
        exceptionHandlers = Collections.unmodifiableMap(new LinkedHashMap<>(exceptionHandlers));
        statusPages = Collections.unmodifiableMap(new LinkedHashMap<>(statusPages));
        staticFiles = List.copyOf(staticFiles);
    }
}
