package flashcard.service;

import java.util.ArrayList;
import java.util.List;

import flashcard.domain.CardWithTags;

/**
 * Reads and writes vocabulary CSV.
 * Format: text,meaning,tag1;tag2 (the tag column is optional,
 * values containing commas are wrapped in double quotes)
 */
public class CsvCodec {

    public record CsvCard(String text, String meaning, List<String> tags) {
    }

    public static List<CsvCard> parse(String content) {
        List<CsvCard> cards = new ArrayList<>();
        int lineNumber = 0;
        for (String line : content.lines().toList()) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            List<String> columns = splitLine(line);
            if (columns.size() < 2 || columns.get(0).isBlank() || columns.get(1).isBlank()) {
                throw new CsvFormatException(lineNumber, line);
            }
            List<String> tags = columns.size() >= 3 && !columns.get(2).isBlank()
                    ? List.of(columns.get(2).split(";"))
                    : List.of();
            cards.add(new CsvCard(columns.get(0).trim(), columns.get(1).trim(),
                    tags.stream().map(String::trim).filter(tag -> !tag.isBlank()).toList()));
        }
        return cards;
    }

    /** A single-line parser supporting the double-quote rules. */
    static List<String> splitLine(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        columns.add(current.toString());
        return columns;
    }

    public static String format(List<CardWithTags> cards) {
        StringBuilder csv = new StringBuilder();
        for (CardWithTags cardWithTags : cards) {
            csv.append(quote(cardWithTags.card().text()))
                    .append(',')
                    .append(quote(cardWithTags.card().meaning()))
                    .append(',')
                    .append(quote(String.join(";", cardWithTags.tags())))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String quote(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
