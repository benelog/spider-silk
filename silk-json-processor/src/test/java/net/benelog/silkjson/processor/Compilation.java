package net.benelog.silkjson.processor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Runs javac in memory with the processor on, over sources given as strings,
 * and keeps what came out: the diagnostics, the generated sources, and the
 * compiled classes, which {@link Result#load} defines in a class loader whose
 * parent is the test's, so the generated codec sees spider-silk-core.
 */
final class Compilation {

    private Compilation() {
    }

    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics,
            Map<String, String> generated, Map<String, byte[]> classes) {

        List<String> errors() {
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(diagnostic.getMessage(null));
                }
            }
            return errors;
        }

        String generated(String qualifiedName) {
            String source = generated.get(qualifiedName);
            if (source == null) {
                throw new AssertionError("Nothing generated as " + qualifiedName + "; generated: " + generated.keySet()
                        + "; errors: " + errors());
            }
            return source;
        }

        Class<?> load(String qualifiedName) {
            if (!success) {
                throw new AssertionError("The compilation failed: " + errors());
            }
            ClassLoader loader = new ClassLoader(Compilation.class.getClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name);
                    if (bytes == null) {
                        throw new ClassNotFoundException(name);
                    }
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            try {
                return loader.loadClass(qualifiedName);
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }
    }

    static Result compile(Map<String, String> sources, String... options) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        Map<String, String> generated = new LinkedHashMap<>();
        Map<String, byte[]> classes = new LinkedHashMap<>();
        JavaFileManager manager = new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                    FileObject sibling) {
                URI uri = URI.create("memory:///" + className.replace('.', '/') + kind.extension);
                return new SimpleJavaFileObject(uri, kind) {
                    private byte[] content = new byte[0];

                    @Override
                    public OutputStream openOutputStream() {
                        return new ByteArrayOutputStream() {
                            @Override
                            public void close() throws IOException {
                                super.close();
                                content = toByteArray();
                                if (kind == JavaFileObject.Kind.SOURCE) {
                                    generated.put(className, new String(content, StandardCharsets.UTF_8));
                                } else {
                                    classes.put(className, content);
                                }
                            }
                        };
                    }

                    @Override
                    public InputStream openInputStream() {
                        return new ByteArrayInputStream(content);
                    }

                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return new String(content, StandardCharsets.UTF_8);
                    }
                };
            }
        };
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, text) -> units.add(new SimpleJavaFileObject(
                URI.create("memory:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        }));
        List<String> arguments = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-Xlint:none", "-implicit:none"));
        arguments.addAll(List.of(options));
        StringWriter log = new StringWriter();
        JavaCompiler.CompilationTask task = compiler.getTask(log, manager, diagnostics, arguments, null, units);
        task.setProcessors(List.of(new JsonProcessor()));
        boolean success = task.call();
        return new Result(success, diagnostics.getDiagnostics(), generated, classes);
    }
}
