package net.benelog.spidersilk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import jakarta.servlet.http.Part;

/**
 * A single file uploaded via multipart.
 *
 * <p>The content is read once, in whichever shape the handler needs it:
 * {@link #bytes()} and {@link #asText()} hold it in memory, {@link #inputStream()}
 * and {@link #writeTo(Path)} do not.
 */
public final class UploadedFile {

    private final Part part;

    UploadedFile(Part part) {
        this.part = part;
    }

    public String fileName() {
        return part.getSubmittedFileName();
    }

    public String contentType() {
        return part.getContentType();
    }

    public long size() {
        return part.getSize();
    }

    public byte[] bytes() {
        try (InputStream in = inputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String asText() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    /**
     * The content as bytes, unread — what a parser from another library wants,
     * so an upload of any size is never a {@code byte[]} first.
     *
     * <pre>{@code
     * try (InputStream in = req.file("csv").inputStream()) {
     *     imported = deckService.importCsv(deckId, in);
     * }
     * }</pre>
     *
     * <p>The caller closes it. This is {@link WebRequest#bodyStream()} for one
     * part rather than for the whole body, and the same rule applies: what has
     * been read is read, so a handler that also wants {@link #bytes()} reads the
     * stream into its own buffer instead of asking twice.
     */
    public InputStream inputStream() {
        try {
            return part.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes the content to a file, without holding it in memory.
     *
     * <pre>{@code
     * req.file("csv").writeTo(uploads.resolve(UUID.randomUUID() + ".csv"));
     * }</pre>
     *
     * <p>The target is an absolute path, or a relative one resolved against the
     * working directory. A container that had already buffered the upload to
     * disk may move that file rather than copy it, so the content is written
     * once: read it before writing it, not after.
     *
     * <p>What happens to a file already at the target is the container's to
     * decide, and the servlet API does not say, so write to a name nothing else
     * holds.
     *
     * @throws UncheckedIOException if the file cannot be written
     */
    public void writeTo(Path target) {
        try {
            part.write(target.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
