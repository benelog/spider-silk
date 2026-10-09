package net.benelog.spidersilk.gradle;

import gg.jte.ContentType;
import gg.jte.gradle.JteExtension;
import org.gradle.api.Project;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import javax.inject.Inject;

/**
 * The {@code spiderSilk} block. It carries no properties, only opt-ins for
 * the parts of the packaging that not every application uses.
 */
public class SpiderSilkExtension {

    /** Kept in step with the jte the plugin's build pins, and with spider-silk-core's. */
    static final String JTE_VERSION = "3.2.4";

    /** The jakarta.json.bind API whose annotations the generated codecs read. */
    static final String JSONB_API_VERSION = "3.0.1";

    /** The plugin's own version, which is the version of the framework jars it adds. */
    static final String VERSION = version();

    private final Project project;

    @Inject
    public SpiderSilkExtension(Project project) {
        this.project = project;
    }

    /**
     * Precompiled jte templates, the way the manual's Templates chapter sets
     * them up: every template under {@code src/main/resources/jte} becomes a
     * Java class at build time, so the jar renders without a runtime compiler
     * and a template that does not compile fails the build rather than the
     * request. The native-resources extension rides along, emitting the
     * reflection config a native image needs for the generated classes.
     */
    public void jte() {
        project.getPluginManager().apply("gg.jte.gradle");
        JteExtension jte = project.getExtensions().getByType(JteExtension.class);
        jte.getSourceDirectory().set(project.file("src/main/resources/jte").toPath());
        jte.getContentType().set(ContentType.Html);
        jte.generate();
        jte.jteExtension("gg.jte.nativeimage.NativeResourcesExtension");
        project.getDependencies().add("jteGenerate", "gg.jte:jte-native-resources:" + JTE_VERSION);
    }

    /**
     * Generated JSON codecs, the way the manual's JSON chapter sets them up:
     * the annotation processor on javac's processor path, so every type
     * annotated {@code @JsonBound} gets its codec at compile time, and the
     * {@code jakarta.json.bind} annotations on the compile classpath, where
     * they are read and never needed again. Nothing is added at run time.
     */
    public void json() {
        project.getDependencies().add("annotationProcessor", "net.benelog.spidersilk:spider-silk-json-processor:" + VERSION);
        project.getDependencies().add("compileOnly", "jakarta.json.bind:jakarta.json.bind-api:" + JSONB_API_VERSION);
    }

    private static String version() {
        Properties properties = new Properties();
        try (InputStream in = SpiderSilkExtension.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                throw new IllegalStateException("version.properties is missing from the plugin jar");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("version");
    }
}
