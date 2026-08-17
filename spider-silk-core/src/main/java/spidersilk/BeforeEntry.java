package spidersilk;

/** A before-handler and the paths it applies to. */
record BeforeEntry(PathPattern pattern, BeforeFilter filter) {

    boolean matches(String[] segments) {
        return pattern.match(segments) != null;
    }
}
