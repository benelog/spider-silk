package net.benelog.spidersilk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The {@code Accept} family of headers, parsed once so no application has to
 * parse it again: {@code Accept}, {@code Accept-Encoding}, and anything else
 * built on the same grammar of comma-separated values with a {@code q=} weight.
 *
 * <p>Matching is by specificity, as the specification asks: {@code text/html}
 * answers before {@code text/*}, which answers before any type at all. A value the
 * client weighted {@code q=0} is one it refused, which is not the same as one it
 * never mentioned — a refusal is honoured even when a wildcard would otherwise
 * have covered it.
 */
final class AcceptHeader {

    /** One entry of the header: what the client will take, and how much it wants it. */
    private record Entry(String value, double quality, int position) {
    }

    private AcceptHeader() {
    }

    /**
     * What the client will take, best first, with the values it refused dropped.
     * Weights are gone by then: the order is the answer they produced.
     */
    static List<String> preferences(String header) {
        List<Entry> entries = new ArrayList<>(parse(header));
        entries.sort(Comparator.comparingDouble(Entry::quality).reversed()
                .thenComparingInt(entry -> wildcards(entry.value()))
                .thenComparingInt(Entry::position));
        List<String> ordered = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.quality() > 0) {
                ordered.add(entry.value());
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * The candidate the client prefers, or null when it will take none of them.
     * An absent header is a client that never said, which the specification
     * reads as "anything" — so the first candidate wins, the list being the
     * order the handler prefers. Candidates the client weighted equally are
     * settled the same way.
     */
    static String best(String header, List<String> candidates) {
        if (header == null || header.isBlank()) {
            return candidates.get(0);
        }
        List<Entry> entries = parse(header);
        String best = null;
        double bestQuality = 0;
        for (String candidate : candidates) {
            double quality = quality(entries, candidate);
            if (quality > bestQuality) {
                bestQuality = quality;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Whether the client will take this value — the {@code Accept-Encoding}
     * question, where the answer is yes or no rather than which one. A header
     * that was never sent is a client that never said it could read gzip, which
     * is why an absent header is a no here and an "anything" in {@link #best}.
     */
    static boolean accepts(String header, String value) {
        return header != null && quality(parse(header), value) > 0;
    }

    /**
     * How much the client wants this value, judged by the entry that describes
     * it most closely. An {@code Accept} of {@code text/html;q=0} followed by a
     * wildcard is a client that will take anything except HTML, so the exact
     * entry decides and the wildcard does not get a say.
     */
    private static double quality(List<Entry> entries, String value) {
        String wanted = strip(value);
        int matched = 0;
        double quality = 0;
        for (Entry entry : entries) {
            int specificity = specificity(entry.value(), wanted);
            if (specificity > matched) {
                matched = specificity;
                quality = entry.quality();
            }
        }
        return quality;
    }

    /**
     * How closely an entry describes a value: exactly, by type, by wildcard, or
     * not at all. Zero means the entry has nothing to say about it.
     */
    private static int specificity(String entry, String value) {
        if (entry.equals(value)) {
            return 3;
        }
        if (entry.equals("*") || entry.equals("*/*")) {
            return 1;
        }
        int star = entry.indexOf("/*");
        if (star > 0 && star == entry.length() - 2) {
            return value.startsWith(entry.substring(0, star + 1)) ? 2 : 0;
        }
        return 0;
    }

    private static List<Entry> parse(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (String element : header.split(",", -1)) {
            String[] parts = element.split(";");
            String value = strip(parts[0]);
            if (value.isEmpty()) {
                continue;
            }
            entries.add(new Entry(value, quality(parts), entries.size()));
        }
        return entries;
    }

    /** The {@code q=} weight of one entry. Anything unreadable counts as full weight. */
    private static double quality(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            String parameter = parts[i].trim();
            if (!parameter.regionMatches(true, 0, "q=", 0, 2)) {
                continue;
            }
            try {
                return Double.parseDouble(parameter.substring(2).trim());
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        return 1;
    }

    /** A media type without its parameters, compared without regard for case. */
    private static String strip(String value) {
        String stripped = value.trim();
        int semicolon = stripped.indexOf(';');
        if (semicolon >= 0) {
            stripped = stripped.substring(0, semicolon).trim();
        }
        return stripped.toLowerCase(Locale.ROOT);
    }

    /** How much of the entry is a wildcard, for ordering equally wanted entries. */
    private static int wildcards(String value) {
        int stars = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '*') {
                stars++;
            }
        }
        return stars;
    }
}
