package spidersilk;

/**
 * A before-handler and the paths it applies to.
 *
 * <p>{@code path} is the pattern as it was written, which {@link PathPattern}
 * does not keep: it is what {@link App#guards()} reports.
 */
record BeforeEntry(String path, PathPattern pattern, BeforeFilter filter) {

    BeforeEntry(String path, BeforeFilter filter) {
        this(path, new PathPattern(path), filter);
    }

    boolean matches(String[] segments) {
        return pattern.match(segments) != null;
    }
}
