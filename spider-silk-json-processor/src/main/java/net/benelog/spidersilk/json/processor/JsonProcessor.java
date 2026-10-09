package net.benelog.spidersilk.json.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Generates a {@code JsonCodec} for every type annotated {@code @JsonBound}.
 * javac finds the processor through {@code META-INF/services}, so a build
 * needs only the processor on its {@code annotationProcessor} path:
 *
 * <pre>{@code
 * dependencies {
 *     annotationProcessor 'net.benelog.spidersilk:spider-silk-json-processor:1.1.0'
 * }
 * }</pre>
 *
 * <p>The option {@code -Aspidersilk.json.names=explicit} makes a property
 * without a {@code @JsonbProperty} name a compile error, for a build that
 * wants every name on the wire written down.
 */
public final class JsonProcessor extends AbstractProcessor {

    static final String NAMES_OPTION = "spidersilk.json.names";

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

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        TypeElement jsonBound = processingEnv.getElementUtils().getTypeElement(Annotations.JSON_BOUND);
        if (jsonBound == null) {
            return false;
        }
        boolean explicitNames = "explicit".equals(processingEnv.getOptions().get(NAMES_OPTION));
        for (Element element : round.getElementsAnnotatedWith(jsonBound)) {
            if (!(element instanceof TypeElement annotated)) {
                continue;
            }
            try {
                BoundType type = BoundType.of(annotated, processingEnv, explicitNames);
                String source = CodecSource.of(type);
                JavaFileObject file = processingEnv.getFiler().createSourceFile(type.generatedQualifiedName(), annotated);
                try (Writer writer = file.openWriter()) {
                    writer.write(source);
                }
            } catch (Invalid e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.element());
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "The codec could not be written: " + e.getMessage(), annotated);
            }
        }
        return false;
    }
}
