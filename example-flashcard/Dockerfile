# A Dockerfile for platforms that build from one and nothing else; where the
# choice exists, the Jib tasks in build.gradle produce the same image without
# a Docker daemon. Build from the repository root, so the context holds every
# module of the multi-module build:
#
#   docker build -f example-flashcard/Dockerfile -t spider-silk-flashcard .
#
# The stages are ordered for the layer cache: build scripts before sources,
# dependency download before compilation, and the runtime image is layered
# the way Jib layers it — external jars, project jars, resources, classes —
# with the application as loose .class files rather than a jar, so a layer's
# content changes only when bytecode or a resource does, never because an
# archive was repackaged.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Build scripts only — a source edit leaves these layers, and the dependency
# download below, untouched. settings.gradle names every module, so every
# module's build script has to be here for configuration to succeed; COPY
# flattens globs, hence one line per module. The spider-silk Gradle plugin is
# an included build that settings.gradle references at configuration time, so
# it comes along whole — an edit to the plugin invalidates the dependency
# layer, which is rare and accepted.
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY spider-silk-gradle-plugin/ spider-silk-gradle-plugin/
COPY spider-silk-maven-parent/ spider-silk-maven-parent/
COPY spider-silk-core/build.gradle spider-silk-core/
COPY spider-silk-test/build.gradle spider-silk-test/
COPY spider-silk-tomcat/build.gradle spider-silk-tomcat/
COPY spider-silk-undertow/build.gradle spider-silk-undertow/
COPY spider-silk-freemarker/build.gradle spider-silk-freemarker/
COPY spider-silk-handlebars/build.gradle spider-silk-handlebars/
COPY spider-silk-thymeleaf/build.gradle spider-silk-thymeleaf/
COPY example-flashcard/build.gradle example-flashcard/
RUN ./gradlew --no-daemon :example-flashcard:resolveDependencies

# Sources last; an edit here invalidates the cache only from this point on.
COPY spider-silk-core/src spider-silk-core/src
COPY example-flashcard/src example-flashcard/src
RUN ./gradlew --no-daemon :example-flashcard:installDist

# Sort the runtime classpath into Jib's tiers: external jars apart from the
# spider-silk-core jar (the project dependency — its own tier, so a framework
# edit leaves the larger external layer untouched). The application's own jar
# is discarded: it ships as the loose classes and resources copied below.
# An application pulling spider-silk from a repository has no core tier —
# the framework jars are external there, changing only on a version bump.
RUN set -e; lib=example-flashcard/build/install/example-flashcard/lib; \
    mkdir -p /layers/dependencies /layers/core; \
    mv "$lib"/spider-silk-*.jar /layers/core/; \
    rm "$lib"/example-flashcard-*.jar; \
    mv "$lib"/*.jar /layers/dependencies/

FROM eclipse-temurin:21-jre
WORKDIR /app

# Four COPY instructions, least volatile first, each cached by content: a
# code edit rewrites only the classes layer, and the big external-jar layer
# survives everything short of a dependency bump. Loose classes keep that
# honest even where an archive would not reproduce byte-for-byte: unchanged
# bytecode is unchanged content, with no repackaging in between. One caveat
# Jib does not have: Docker caches these instructions as a chain, so an edit
# rewrites its own tier and the tiers after it — which is why the most
# volatile tier sits last.
COPY --from=build /layers/dependencies lib/
COPY --from=build /layers/core lib/
COPY --from=build /build/example-flashcard/build/resources/main resources/
COPY --from=build /build/example-flashcard/build/classes/java/main classes/

EXPOSE 8080
# The classpath states what the jar's manifest no longer can, in Jib's order;
# java expands lib/* itself, no shell involved. H2 writes its database under
# the process home, /root/db here, so mount a volume there to keep the data
# past the container.
ENTRYPOINT ["java", "-cp", "/app/resources:/app/classes:/app/lib/*", "flashcard.FlashcardApp"]
