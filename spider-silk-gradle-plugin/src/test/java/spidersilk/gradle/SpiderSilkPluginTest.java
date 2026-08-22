package spidersilk.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the plugin against a scratch project with TestKit. What is asserted is
 * the configuration the plugin promises — base images, ports, tasks, the
 * -Pnative switch — not a full image build, which needs registries and a
 * GraalVM the test environment does not.
 */
class SpiderSilkPluginTest {

    @TempDir
    Path projectDir;

    @BeforeEach
    void scratchProject() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'scratch'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.github.benelog.spidersilk'
                }
                repositories {
                    mavenCentral()
                }
                application {
                    mainClass = 'scratch.Main'
                }
                jib {
                    to { image = 'ghcr.io/example/scratch' }
                }
                tasks.register('probe') {
                    doLast {
                        println "from=" + jib.from.image
                        println "to=" + jib.to.image
                        println "ports=" + jib.container.ports
                        println "metadataRepository=" + graalvmNative.metadataRepository.enabled.get()
                        println "buildArgs=" + graalvmNative.binaries.main.buildArgs.get()
                        println "targetCompatibility=" + java.targetCompatibility
                        println "jibDependsOnNativeCompile=" + tasks.jibBuildTar.dependsOn.contains(tasks.named('nativeCompile'))
                    }
                }
                tasks.withType(JavaCompile).configureEach {
                    options.release = 21
                }
                """);
    }

    private BuildResult probe(String... extraArguments) {
        String[] arguments = new String[extraArguments.length + 2];
        arguments[0] = "probe";
        arguments[1] = "-q";
        System.arraycopy(extraArguments, 0, arguments, 2, extraArguments.length);
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    @Test
    void jvmImageDefaults() {
        String output = probe().getOutput();

        assertThat(output).contains("from=" + SpiderSilkPlugin.JRE_BASE_IMAGE);
        assertThat(output).contains("to=ghcr.io/example/scratch");
        assertThat(output).contains("ports=[8080]");
        assertThat(output).contains("metadataRepository=true");
        assertThat(output).contains("--no-fallback");
        assertThat(output).contains("--static-nolibc");
        assertThat(output).contains("targetCompatibility=21");
        assertThat(output).contains("jibDependsOnNativeCompile=false");
    }

    @Test
    void nativePropertySwitchesTheImage() {
        String output = probe("-Pnative").getOutput();

        assertThat(output).contains("from=" + SpiderSilkPlugin.NATIVE_BASE_IMAGE);
        assertThat(output).contains("to=ghcr.io/example/scratch:native");
        assertThat(output).contains("jibDependsOnNativeCompile=true");
    }

    @Test
    void resolveDependenciesTaskIsRegistered() {
        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("resolveDependencies", "-q")
                .build();

        assertThat(result.getOutput()).doesNotContain("FAILED");
    }

    @Test
    void jteWiresPrecompilationAndNativeResources() throws IOException {
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'io.github.benelog.spidersilk'
                }
                repositories {
                    mavenCentral()
                }
                spiderSilk {
                    jte()
                }
                application {
                    mainClass = 'scratch.Main'
                }
                tasks.register('probe') {
                    doLast {
                        println "generateJte=" + tasks.names.contains('generateJte')
                        println "jteSourceDirectory=" + jte.sourceDirectory.get()
                        println "jteGenerate=" + configurations.jteGenerate.dependencies*.name
                    }
                }
                """);

        String output = probe().getOutput();

        assertThat(output).contains("generateJte=true");
        assertThat(output).contains("jteSourceDirectory=" + projectDir.toRealPath().resolve("src/main/resources/jte"));
        assertThat(output).contains("jte-native-resources");
    }
}
