package net.benelog.spidersilk.gradle;

import gg.jte.ContentType;
import gg.jte.gradle.JteExtension;
import org.gradle.api.Project;

import javax.inject.Inject;

/**
 * The {@code spiderSilk} block. It carries no properties, only opt-ins for
 * the parts of the packaging that not every application uses.
 */
public class SpiderSilkExtension {

    /** Kept in step with the jte the plugin's build pins, and with spider-silk-core's. */
    static final String JTE_VERSION = "3.2.4";

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
}
