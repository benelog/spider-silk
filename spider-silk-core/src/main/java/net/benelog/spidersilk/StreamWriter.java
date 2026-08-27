package net.benelog.spidersilk;

import java.io.OutputStream;

/**
 * Writes the body of a {@link WebResponse#stream(String, StreamWriter)}
 * response. The stream is closed for you once the writer returns.
 */
@FunctionalInterface
public interface StreamWriter {

    void write(OutputStream out) throws Exception;
}
