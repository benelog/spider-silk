package net.benelog.silkjson;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A member name encoded once. A generated codec, or a writer that names the
 * same keys for every record, holds one per key: {@link JsonOutput} copies its
 * bytes instead of escaping the name again, and {@link JsonInput} compares a
 * parsed key against it without making a string.
 *
 * <pre>{@code
 * static final JsonKey ID = JsonKey.of("id");
 *
 * out.object().put(ID, deck.id()).end();
 * if (in.keyIs(ID)) { ... }
 * }</pre>
 */
public final class JsonKey {

    private final String name;

    /** The name's own UTF-8, with no quote and no escape: what a parsed key is compared with. */
    final byte[] utf8;

    /** {@link #utf8} eight bytes to a word, compared a word at a time, and the mask of the last word's bytes. */
    final long[] utf8Words;
    final long lastMask;

    /** The name as the first member of an object is written, quoted and escaped, with the colon after it, eight bytes to a word. */
    final long[] first;
    final int firstLength;

    /** The same with the comma that comes before every member after the first. */
    final long[] next;
    final int nextLength;

    /**
     * The first two words of each form and the masks of the bytes they hold,
     * as fields: a parser expecting this key compares the bytes where the key
     * starts with these, read off a constant with no array in between, and
     * has the key and its colon at once. A name of up to 12 bytes fits in them.
     */
    final long first0;
    final long first1;
    final long firstMask0;
    final long firstMask1;
    final long next0;
    final long next1;
    final long nextMask0;
    final long nextMask1;

    /** Whether the name is written with an escape, and whether it is all ASCII: what a parser notes of a key it matched in place. */
    final boolean escaped;
    final boolean ascii;

    private JsonKey(String name) {
        this.name = name;
        this.utf8 = name.getBytes(StandardCharsets.UTF_8);
        long[] words = LittleEndian.words(utf8);
        this.utf8Words = words.length > 0 ? words : new long[1];
        this.lastMask = mask(utf8.length, utf8Words.length - 1);
        JsonOutput out = JsonOutput.inMemory();
        out.array().value(name).end();
        byte[] written = out.toBytes();
        // [ "name" ] with a comma for the bracket and a colon for the other one: the member after the first.
        byte[] member = written.clone();
        member[0] = ',';
        member[member.length - 1] = ':';
        this.next = LittleEndian.words(member);
        this.nextLength = member.length;
        this.first = LittleEndian.words(Arrays.copyOfRange(member, 1, member.length));
        this.firstLength = member.length - 1;
        this.first0 = first[0];
        this.first1 = first.length > 1 ? first[1] : 0;
        this.firstMask0 = mask(firstLength, 0);
        this.firstMask1 = mask(firstLength, 1);
        this.next0 = next[0];
        this.next1 = next.length > 1 ? next[1] : 0;
        this.nextMask0 = mask(nextLength, 0);
        this.nextMask1 = mask(nextLength, 1);
        this.escaped = firstLength != utf8.length + 3;
        this.ascii = name.chars().allMatch(c -> c < 0x80);
    }

    /** The mask of the bytes that word holds of so many bytes laid eight to a word: all eight, the low ones of the last, or none past it. */
    private static long mask(int length, int word) {
        int bytes = length - (word << 3);
        if (bytes <= 0) {
            return 0;
        }
        return bytes >= 8 ? -1L : (1L << (bytes << 3)) - 1;
    }

    public static JsonKey of(String name) {
        return new JsonKey(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
