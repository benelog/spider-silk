package net.benelog.spidersilk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;

import org.jspecify.annotations.Nullable;

/**
 * The lines of an NDJSON body, split on the bytes as they arrive so that no
 * line is held past its limit.
 *
 * <p>{@code BufferedReader.lines()} finishes a line before anyone can look at
 * it, so a body with no newline at all is one line of any size. This reads a
 * chunk at a time and refuses a line the moment it outgrows the limit, which
 * keeps what is in memory to one line and one chunk however long the body is.
 *
 * <p>A line ends at {@code \n}, with a {@code \r} before it dropped, which is
 * what NDJSON allows. Splitting on the byte is sound for UTF-8 and every other
 * charset that encodes {@code \n} as that one byte, since no other character
 * contains it. A failure is kept and thrown again on every later advance, so
 * a caller that catches it cannot read on from the middle of a refused line.
 */
final class NdjsonLines extends Spliterators.AbstractSpliterator<String> {

    private static final int CHUNK = 8192;

    private final InputStream in;
    private final Charset charset;
    private final int maxLineBytes;
    private final LongFunction<RuntimeException> tooLarge;
    private final Function<IOException, RuntimeException> unreadable;

    private final byte[] chunk = new byte[CHUNK];
    private int position;
    private int filled;

    private byte[] line;
    private int length;
    private long number;

    private boolean ended;
    private @Nullable RuntimeException failure;

    /**
     * @param tooLarge what to throw for a line over the limit, given its number,
     *                 counted from 1
     * @param unreadable what to throw for a body the container could not
     *                   finish reading, given its failure
     */
    NdjsonLines(InputStream in, Charset charset, int maxLineBytes, LongFunction<RuntimeException> tooLarge,
            Function<IOException, RuntimeException> unreadable) {
        super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
        this.in = in;
        this.charset = charset;
        this.maxLineBytes = maxLineBytes;
        this.tooLarge = tooLarge;
        this.unreadable = unreadable;
        this.line = new byte[(int) Math.min(CHUNK, maxLineBytes + 1L)];
    }

    @Override
    public boolean tryAdvance(Consumer<? super String> action) {
        if (failure != null) {
            throw failure;
        }
        if (ended) {
            return false;
        }
        String next;
        try {
            next = nextLine();
        } catch (IOException e) {
            failure = unreadable.apply(e);
            throw failure;
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        }
        if (next == null) {
            ended = true;
            return false;
        }
        action.accept(next);
        return true;
    }

    /** The next line, or null at the end of the body. */
    private @Nullable String nextLine() throws IOException {
        length = 0;
        boolean started = false;
        while (true) {
            if (position == filled) {
                int read = in.read(chunk);
                if (read < 0) {
                    // A last line with no newline after it is still a line.
                    return started ? finish() : null;
                }
                position = 0;
                filled = read;
            }
            started = true;
            int start = position;
            while (position < filled && chunk[position] != '\n') {
                position++;
            }
            append(start, position - start);
            if (position < filled) {
                position++;
                return finish();
            }
        }
    }

    /**
     * Adds bytes to the line, refusing it once it is past what could still turn
     * out to be the limit plus the {@code \r} a {@code \r\n} ending leaves.
     */
    private void append(int start, int count) {
        if (length + (long) count > maxLineBytes + 1L) {
            throw tooLarge.apply(number + 1);
        }
        if (length + count > line.length) {
            int grown = (int) Math.min(Math.max(line.length * 2L, length + (long) count), maxLineBytes + 1L);
            line = Arrays.copyOf(line, grown);
        }
        System.arraycopy(chunk, start, line, length, count);
        length += count;
    }

    private String finish() {
        number++;
        int end = length > 0 && line[length - 1] == '\r' ? length - 1 : length;
        if (end > maxLineBytes) {
            throw tooLarge.apply(number);
        }
        return new String(line, 0, end, charset);
    }
}
