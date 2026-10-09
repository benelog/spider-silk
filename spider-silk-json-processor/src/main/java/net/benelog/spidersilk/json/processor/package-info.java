/**
 * The annotation processor behind {@code @JsonBound}: it reads a type's
 * components, fields, and accessors, and the {@code jakarta.json.bind}
 * annotations on them, and writes a {@code JsonCodec} for the type as Java
 * source, which javac compiles beside the application's own code.
 */
@NullMarked
package net.benelog.spidersilk.json.processor;

import org.jspecify.annotations.NullMarked;
