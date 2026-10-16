package net.benelog.spidersilk;

import java.util.Locale;
import java.util.Map;

/** Extension-to-Content-Type mapping, for static files and for {@link WebResponse#file}. */
final class ContentTypes {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("html", "text/html; charset=UTF-8"),
            Map.entry("htm", "text/html; charset=UTF-8"),
            Map.entry("css", "text/css; charset=UTF-8"),
            Map.entry("js", "application/javascript; charset=UTF-8"),
            // A browser runs a module script only when it is served as JavaScript.
            Map.entry("mjs", "text/javascript; charset=UTF-8"),
            Map.entry("json", "application/json"),
            // A source map is JSON.
            Map.entry("map", "application/json"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("ndjson", "application/x-ndjson"),
            Map.entry("xml", "application/xml"),
            Map.entry("txt", "text/plain; charset=UTF-8"),
            Map.entry("csv", "text/csv; charset=UTF-8"),
            Map.entry("pdf", "application/pdf"),
            // WebAssembly.instantiateStreaming refuses any other type.
            Map.entry("wasm", "application/wasm"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
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
