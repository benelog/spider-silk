package net.benelog.spidersilk;

/**
 * An after-handler and the paths it applies to.
 *
 * <p>{@code path} is the pattern as it was written, which {@link PathPattern}
 * does not keep: it is what {@link App#guards()} reports.
 */
record AfterEntry(String path, PathPattern pattern, AfterFilter filter) {

    AfterEntry(String path, AfterFilter filter) {
        this(path, new PathPattern(path), filter);
    }

    boolean matches(String[] segments) {
        return pattern.match(segments) != null;
    }
}
