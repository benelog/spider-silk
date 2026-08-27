package net.benelog.spidersilk.gradle;

import com.google.cloud.tools.jib.gradle.JibExtension;
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension;
import org.graalvm.buildtools.gradle.dsl.GraalVMReachabilityMetadataRepositoryExtension;
import org.gradle.api.JavaVersion;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.compile.JavaCompile;

import java.util.List;
import java.util.Map;

/**
 * Packaging conventions for a spider-silk application, so the build file
 * states only what is the application's own: the main class, the image name,
 * and the native binary's resources.
 *
 * <p>Applying the plugin applies {@code application}, Jib, and the GraalVM
 * native-image plugin, and sets the conventions the manual's Deployment
 * chapter walks through: a JRE 21 base image, port 8080, the reachability
 * metadata repository, {@code --no-fallback} (a fallback image would reintroduce
 * the reflection the framework exists to avoid), and a {@code resolveDependencies}
 * task for a Dockerfile's dependency-cache layer. {@code -Pnative} re-aims the
 * same Jib tasks at the native binary. All of it stays overridable through the
 * plugins' own DSLs, which run after these defaults.
 */
public class SpiderSilkPlugin implements Plugin<Project> {

    static final String JRE_BASE_IMAGE = "eclipse-temurin:21-jre";
    static final String NATIVE_BASE_IMAGE = "gcr.io/distroless/base-debian12";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("application");
        project.getPluginManager().apply("com.google.cloud.tools.jib");
        project.getPluginManager().apply("org.graalvm.buildtools.native");

        project.getExtensions().create("spiderSilk", SpiderSilkExtension.class, project);

        JibExtension jib = project.getExtensions().getByType(JibExtension.class);
        jib.getFrom().setImage(JRE_BASE_IMAGE);
        jib.getContainer().setPorts(List.of("8080"));

        GraalVMExtension graalvm = project.getExtensions().getByType(GraalVMExtension.class);
        // The metadata repository is a nested extension on graalvmNative, not a getter.
        ((ExtensionAware) graalvm).getExtensions()
                .getByType(GraalVMReachabilityMetadataRepositoryExtension.class)
                .getEnabled().set(true);
        graalvm.getBinaries().configureEach(binary -> {
            if (binary.getName().equals("main")) {
                binary.getBuildArgs().add("--no-fallback");
                // Mostly static: everything but glibc links in, so the binary
                // needs nothing from the distroless/base image beyond glibc —
                // dynamically linked, it would die on the libz that image lacks.
                binary.getBuildArgs().add("--static-nolibc");
            }
        });

        registerResolveDependencies(project);

        project.afterEvaluate(p -> {
            alignTargetCompatibility(p);
            if (p.getProviders().gradleProperty("native").isPresent()) {
                switchJibToTheNativeBinary(p, jib, graalvm);
            }
        });
    }

    /**
     * Jib checks the application's {@code targetCompatibility} against the base
     * image's Java version, and a build that targets through {@code options.release}
     * alone leaves it at the toolchain's version, failing the check. Restating
     * the release there closes the gap without the build file having to.
     */
    private void alignTargetCompatibility(Project project) {
        JavaCompile compileJava = (JavaCompile) project.getTasks().getByName("compileJava");
        Integer release = compileJava.getOptions().getRelease().getOrNull();
        if (release != null) {
            project.getExtensions().getByType(JavaPluginExtension.class)
                    .setTargetCompatibility(JavaVersion.toVersion(release));
        }
    }

    /**
     * A Dockerfile's warm-up layer runs this before any source is copied in,
     * so the downloaded dependencies survive source edits as a cached layer.
     * The component filter keeps it to external modules: the {@code dependencies}
     * report stops at metadata without fetching a single application jar, and
     * resolving the classpath whole would drag project dependencies' compilation
     * — and therefore their sources — into the layer.
     */
    private void registerResolveDependencies(Project project) {
        project.getTasks().register("resolveDependencies", task -> {
            task.setGroup("build setup");
            task.setDescription("Downloads the external runtime dependencies, for a Dockerfile's dependency-cache layer.");
            task.doLast(t -> project.getConfigurations().getByName("runtimeClasspath").getIncoming()
                    .artifactView(view -> view.componentFilter(id -> id instanceof ModuleComponentIdentifier))
                    .getFiles().getFiles());
        });
    }

    /**
     * {@code -Pnative} re-aims the Jib tasks at the GraalVM binary: a glibc base
     * in place of the JRE, a {@code :native} tag so the JVM image is not
     * overwritten, Jib's native-image extension pointed at the binary the
     * {@code graalvmNative} block names, and the task dependency on
     * {@code nativeCompile} that the extension itself does not declare.
     */
    private void switchJibToTheNativeBinary(Project project, JibExtension jib, GraalVMExtension graalvm) {
        jib.getFrom().setImage(NATIVE_BASE_IMAGE);
        String to = jib.getTo().getImage();
        if (to != null && !hasTag(to)) {
            jib.getTo().setImage(to + ":native");
        }
        String imageName = graalvm.getBinaries().getByName("main").getImageName()
                .getOrElse(project.getName());
        jib.pluginExtensions(spec -> spec.pluginExtension(extension -> {
            extension.setImplementation(
                    "com.google.cloud.tools.jib.gradle.extension.nativeimage.JibNativeImageExtension");
            extension.setProperties(Map.of("imageName", imageName));
        }));
        for (String taskName : List.of("jib", "jibBuildTar", "jibDockerBuild")) {
            project.getTasks().named(taskName)
                    .configure(task -> task.dependsOn(project.getTasks().named("nativeCompile")));
        }
    }

    private static boolean hasTag(String image) {
        return image.indexOf(':', image.lastIndexOf('/') + 1) >= 0;
    }
}
