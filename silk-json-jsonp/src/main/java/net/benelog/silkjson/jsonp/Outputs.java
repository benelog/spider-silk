package net.benelog.silkjson.jsonp;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import jakarta.json.JsonException;

/**
 * The stream a {@link net.benelog.silkjson.JsonOutput} writes UTF-8 into, for
 * each place JSON-P writes to. A byte stream in UTF-8 takes the bytes as they
 * are; a {@link Writer}, or a byte stream in another encoding, takes them
 * decoded into characters. Pretty printing is a filter over the bytes, and
 * every one of these throws {@link JsonException} where the stream it ends in
 * throws {@link IOException}, which is what JSON-P has a generator and a
 * writer throw.
 */
final class Outputs {

    private Outputs() {
    }

    static Guarded of(OutputStream out, boolean pretty) {
        return guarded(Objects.requireNonNull(out, "out"), pretty);
    }

    static Guarded of(OutputStream out, Charset charset, boolean pretty) {
        Objects.requireNonNull(out, "out");
        if (Objects.requireNonNull(charset, "charset").equals(StandardCharsets.UTF_8)) {
            return guarded(out, pretty);
        }
        return guarded(new Chars(new OutputStreamWriter(out, charset)), pretty);
    }

    static Guarded of(Writer writer, boolean pretty) {
        return guarded(new Chars(Objects.requireNonNull(writer, "writer")), pretty);
    }

    private static Guarded guarded(OutputStream out, boolean pretty) {
        return new Guarded(pretty ? new Pretty(out) : out);
    }

    /** Passes everything on, and throws {@link JsonException} for the {@link IOException} of the stream below. */
    static final class Guarded extends OutputStream {

        private final OutputStream out;

        Guarded(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) {
            try {
                out.write(b);
            } catch (IOException e) {
                throw failed(e);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            try {
                out.write(bytes, offset, length);
            } catch (IOException e) {
                throw failed(e);
            }
        }

        @Override
        public void flush() {
            try {
                out.flush();
            } catch (IOException e) {
                throw failed(e);
            }
        }

        @Override
        public void close() {
            try {
                out.close();
            } catch (IOException e) {
                throw failed(e);
            }
        }

        private static JsonException failed(IOException e) {
            return new JsonException("Could not write JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Lays compact JSON out the way JSON-P's reference implementation prints
     * it: a member or an element to a line, indented four spaces a level, a
     * space after each colon, and an empty object or array closed on the line
     * after it opens. It reads the bytes as they pass and keeps the state that
     * takes, inside a string or not and how deep, so nothing about the
     * document is held but a buffer of what is still to send.
     */
    private static final class Pretty extends OutputStream {

        private static final int INDENT = 4;

        private final OutputStream out;
        private final byte[] buffer = new byte[8192];
        private int buffered;

        private int depth;
        private boolean inString;
        private boolean escaped;

        /** Whether the last byte opened an object or an array, whose first line waits on the byte after it. */
        private boolean opened;

        Pretty(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            take((byte) b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            for (int i = offset, end = offset + length; i < end; i++) {
                take(bytes[i]);
            }
        }

        private void take(byte b) throws IOException {
            if (inString) {
                put(b);
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    inString = false;
                }
                return;
            }
            if (opened) {
                opened = false;
                if (b != '}' && b != ']') {
                    newline();
                }
            }
            switch (b) {
                case '{', '[' -> {
                    put(b);
                    depth++;
                    opened = true;
                }
                case '}', ']' -> {
                    depth--;
                    newline();
                    put(b);
                }
                case ',' -> {
                    put(b);
                    newline();
                }
                case ':' -> {
                    put(b);
                    put((byte) ' ');
                }
                case '"' -> {
                    put(b);
                    inString = true;
                }
                default -> put(b);
            }
        }

        private void newline() throws IOException {
            put((byte) '\n');
            for (int i = depth * INDENT; i > 0; i--) {
                put((byte) ' ');
            }
        }

        private void put(byte b) throws IOException {
            if (buffered == buffer.length) {
                send();
            }
            buffer[buffered++] = b;
        }

        private void send() throws IOException {
            out.write(buffer, 0, buffered);
            buffered = 0;
        }

        @Override
        public void flush() throws IOException {
            send();
            out.flush();
        }

        @Override
        public void close() throws IOException {
            send();
            out.close();
        }
    }

    /**
     * Decodes UTF-8 into a {@link Writer}. The bytes arrive in pieces that
     * may end inside a character, whose first bytes wait for the rest in
     * {@link #partial}.
     */
    private static final class Chars extends OutputStream {

        private final Writer writer;
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        private final ByteBuffer partial = ByteBuffer.allocate(4);
        private final CharBuffer chars = CharBuffer.allocate(4096);

        Chars(Writer writer) {
            this.writer = writer;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ByteBuffer in = ByteBuffer.wrap(bytes, offset, length);
            // A character the last piece ended inside is finished a byte at a time.
            while (partial.position() > 0 && in.hasRemaining()) {
                partial.put(in.get());
                partial.flip();
                decode(partial);
                partial.compact();
            }
            decode(in);
            partial.put(in);
        }

        private void decode(ByteBuffer in) throws IOException {
            while (true) {
                CoderResult result = decoder.decode(in, chars, false);
                if (result.isOverflow()) {
                    send();
                } else if (result.isError()) {
                    result.throwException();
                } else {
                    return;
                }
            }
        }

        private void send() throws IOException {
            writer.write(chars.array(), 0, chars.position());
            chars.clear();
        }

        @Override
        public void flush() throws IOException {
            send();
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            send();
            writer.close();
        }
    }
}
