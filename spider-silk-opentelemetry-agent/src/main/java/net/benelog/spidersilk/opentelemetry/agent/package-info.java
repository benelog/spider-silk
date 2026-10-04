/**
 * An extension of the OpenTelemetry Java agent that reports the route a Spider Silk request
 * matched as the server span's {@code http.route}.
 *
 * <p>The jar is handed to the agent with {@code -Dotel.javaagent.extensions}, never put on the
 * application's class path.
 */
@NullMarked
package net.benelog.spidersilk.opentelemetry.agent;

import org.jspecify.annotations.NullMarked;
