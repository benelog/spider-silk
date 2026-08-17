package spidersilk;

/** An after-handler and the paths it applies to. */
record AfterEntry(PathPattern pattern, AfterFilter filter) {

    boolean matches(String[] segments) {
        return pattern.match(segments) != null;
    }
}
