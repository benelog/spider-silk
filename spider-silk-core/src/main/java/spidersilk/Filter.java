package spidersilk;

/** A before/after handler and the paths it applies to. */
record Filter(PathPattern pattern, Handler handler) {

    boolean matches(String[] segments) {
        return pattern.match(segments) != null;
    }
}
