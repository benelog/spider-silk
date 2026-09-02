package net.benelog.spidersilk;

import java.util.Locale;
import java.util.Map;

/** Extension-to-Content-Type mapping, for static files and for {@link WebResponse#file}. */
final class ContentTypes {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("html", "text/html; charset=UTF-8"),
            Map.entry("css", "text/css; charset=UTF-8"),
            Map.entry("js", "application/javascript; charset=UTF-8"),
            Map.entry("json", "application/json"),
            Map.entry("ndjson", "application/x-ndjson"),
            Map.entry("txt", "text/plain; charset=UTF-8"),
            Map.entry("csv", "text/csv; charset=UTF-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2")
    );

    private ContentTypes() {
    }

    static String byPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return BY_EXTENSION.getOrDefault(path.substring(dot + 1).toLowerCase(Locale.ROOT),
                "application/octet-stream");
    }
}
