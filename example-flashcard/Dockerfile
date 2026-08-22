# A Dockerfile for platforms that build from one and nothing else; where the
# choice exists, the Jib tasks in build.gradle produce the same image without
# a Docker daemon. Build from the repository root, so the context holds every
# module of the multi-module build:
#
#   docker build -f example-flashcard/Dockerfile -t spider-silk-flashcard .
#
# The stages are ordered for the layer cache: build scripts before sources,
# dependency download before compilation, and the runtime image copies the
# external jars apart from this build's own, mirroring how Jib layers it.

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

# Sort the runtime classpath the way Jib layers it: the external jars in one
# directory, this build's own in another, so the final stage can copy them as
# separate layers. The spider-silk jars count as "this build's own" only
# because the framework builds alongside the example; an application pulling
# spider-silk from a repository would leave them on the external side, where
# they change on a version bump rather than on every edit.
RUN set -e; lib=example-flashcard/build/install/example-flashcard/lib; \
    mkdir -p /layers/dependencies /layers/project; \
    mv "$lib"/spider-silk-*.jar "$lib"/example-flashcard*.jar /layers/project/; \
    mv "$lib"/*.jar /layers/dependencies/

FROM eclipse-temurin:21-jre
WORKDIR /app

# Two COPY instructions on purpose: COPY caches by content, so a code change
# rewrites only the second, small layer while the external jars stay cached —
# even though the build stage above re-ran.
COPY --from=build /layers/dependencies lib/
COPY --from=build /layers/project lib/

EXPOSE 8080
# The java launcher expands lib/* itself; no shell is involved. H2 writes its
# database under the process home, /root/db here, so mount a volume there to
# keep the data past the container.
ENTRYPOINT ["java", "-cp", "/app/lib/*", "flashcard.FlashcardApp"]
