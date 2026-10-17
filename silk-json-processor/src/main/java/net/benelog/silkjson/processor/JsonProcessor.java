package net.benelog.silkjson.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Generates a {@code JsonCodec} for every type annotated {@code @JsonBound}.
 * javac finds the processor through {@code META-INF/services}, so a build
 * needs only the processor on its {@code annotationProcessor} path:
 *
 * <pre>{@code
 * dependencies {
 *     annotationProcessor 'net.benelog.silkjson:silk-json-processor:1.1.0'
 * }
 * }</pre>
 *
 * <p>The option {@code -Asilkjson.names=explicit} makes a property
 * without a {@code @JsonbProperty} name a compile error, for a build that
 * wants every name on the wire written down.
 */
public final class JsonProcessor extends AbstractProcessor {

    static final String NAMES_OPTION = "silkjson.names";

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(Annotations.JSON_BOUND);
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(NAMES_OPTION);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /** The codecs generated so far, by qualified name, each with the type it binds, so that two types never share one name. */
    private final Map<String, String> generated = new HashMap<>();

    /** The bound types that referred to a type javac had not resolved, by qualified name, tried again each round. */
    private final Set<String> deferred = new LinkedHashSet<>();

    // The processor claims nothing: a claimed @JsonBound would keep a processor
    // that supports "*" from running in a round where it is the only annotation.
    // Nor would claiming it quiet javac's processing lint, which warns about
    // every unclaimed declaration annotation, the codec's @Generated among them.
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        Elements elements = processingEnv.getElementUtils();
        TypeElement jsonBound = elements.getTypeElement(Annotations.JSON_BOUND);
        if (jsonBound == null) {
            return false;
        }
        boolean explicitNames = "explicit".equals(processingEnv.getOptions().get(NAMES_OPTION));
        List<TypeElement> pending = new ArrayList<>();
        for (String name : deferred) {
            TypeElement again = elements.getTypeElement(name);
            if (again != null) {
                pending.add(again);
            }
        }
        deferred.clear();
        for (Element element : round.getElementsAnnotatedWith(jsonBound)) {
            if (element instanceof TypeElement annotated && !pending.contains(annotated)) {
                pending.add(annotated);
            }
        }
        for (TypeElement annotated : pending) {
            generate(annotated, round, explicitNames);
        }
        return false;
    }

    private void generate(TypeElement annotated, RoundEnvironment round, boolean explicitNames) {
        try {
            BoundType type = BoundType.of(annotated, processingEnv, explicitNames);
            String codec = type.generatedQualifiedName();
            String taken = generated.putIfAbsent(codec, type.targetName());
            if (taken != null) {
                throw new Invalid(annotated, "The codec of " + type.targetName() + " would be " + codec
                        + ", the name the codec of " + taken + " already has: rename one of the two types");
            }
            String source = CodecSource.of(type);
            JavaFileObject file = processingEnv.getFiler().createSourceFile(codec, annotated);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (Deferred e) {
            if (round.processingOver()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "The codec could not be generated: the type " + e.getMessage() + " was never resolved", annotated);
            } else {
                deferred.add(annotated.getQualifiedName().toString());
            }
        } catch (Invalid e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.element());
        } catch (IllegalArgumentException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "The codec could not be generated: " + e.getMessage(), annotated);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "The codec could not be written: " + e.getMessage(), annotated);
        }
    }
}
