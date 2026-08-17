package steelspider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.Part;

/** A single file uploaded via multipart. */
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
        try (InputStream in = part.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String asText() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }
}
