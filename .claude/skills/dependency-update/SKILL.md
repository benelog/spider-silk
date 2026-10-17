---
name: dependency-update
description: >-
  Moves this repository's dependencies to their newer releases: the Gradle coordinates and plugins, the Maven
  parent's plugins, the Gradle wrapper, the GitHub Actions, and the Antora packages, plus the copies of a version
  in the manual and the Gradle plugin's source.
  Use this skill when asked to update, bump, or upgrade dependencies, to check what is outdated, or to take a
  new Jetty, Tomcat, jte, Jackson, or Gradle release; it replaces the Dependabot pull requests this repository
  no longer takes.
---

# Dependency updates

`scripts/DependencyUpdates.java` finds and edits; this skill decides, verifies, and reports.
The script reads every file git tracks, looks each dependency up on Maven Central, the Gradle Plugin Portal, npm, GitHub, or services.gradle.org, and edits the version in place.
It counts a release only when its qualifier matches the current one, so a milestone, a release candidate, or a variant build (`2.0.66.android8`) is never proposed.

## Steps

1. **Report.**
   Run `java scripts/DependencyUpdates.java` from the repository root.
   A row marked `(drifted)` is a dependency written at two versions in two places; the older copy is a bug to fix whatever else moves.
   `GITHUB_TOKEN` or `GH_TOKEN` in the environment lifts GitHub's anonymous rate limit (`GH_TOKEN=$(gh auth token)`).
2. **Apply every update within its major.**
   Run `java scripts/DependencyUpdates.java --apply`.
   It prints each edit, then the lines elsewhere that still carry an old version beside the dependency's name.
3. **Decide each newer major one by one** (the `newer major` column), then apply the chosen ones by name: `java scripts/DependencyUpdates.java --apply actions/checkout org.junit`.
   A name given after `--apply` moves only what contains it, to its latest release, major or not.
   - Move without asking: GitHub Actions, the test dependencies (JUnit, AssertJ), Error Prone and NullAway, the Gradle wrapper, Antora, and everything under `benchmark/` that is not Spider Silk's.
     An action's major usually raises the Node it runs on; read its release notes for anything else.
   - Ask before moving: an `api` dependency of a published module (Jetty, Tomcat, Undertow, jte, Jackson, Thymeleaf, FreeMarker, Handlebars), the servlet API, and the OpenTelemetry agent.
     These versions are the ones the framework's users carry, so a major is a change to Spider Silk, with a CHANGELOG line under `[Unreleased]`.
     A Jetty or Tomcat major is usually a new Servlet release as well, which moves `jakarta.servlet-api` and all three containers together or not at all.
4. **Finish what an edit leaves half done.**
   - Gradle wrapper: `./gradlew wrapper --gradle-version <new>`, which regenerates the scripts and the jar beside the properties file the script edited.
   - `package.json`: `npm install`, which rewrites `package-lock.json`.
   - The mentions the script listed: update the build snippets and attributes in `manual/`, the same lines in `manual-ko/`, `skills/`, and the README badges.
     Keep a version that records a measurement, such as the targets in `manual/modules/ROOT/pages/benchmark.adoc`, and leave `CHANGELOG.md` and `notes/` alone.
5. **Check the pairs that must stay in step.**
   - **Jetty:** `spider-silk-core`'s `jetty-ee10-servlet` and `spider-silk-jetty-websocket`'s server are one release, or two mix on a classpath.
   - **jte:** core, the Gradle plugin's build and `SpiderSilkExtension.JTE_VERSION`, the Maven parent's `jte.version`, the benchmark app, and the `jte-version` attribute are one release.
     The script moves each coordinate on its own, so a family whose members were published a day apart can land split; rerun the report until they agree.
   - **Jib:** the Maven plugin (`jib.version`) trails the Gradle plugin upstream (3.5.2 against 3.5.4 when this was written).
     The difference is upstream's, not drift; the script reads each from its own repository.
6. **Look at the base images by hand.**
   The script lists them and looks none up: `eclipse-temurin:25-jre` and `-jdk` name a JDK line, and `gcr.io/distroless/base-debian12` a Debian release.
   They sit in `SpiderSilkPlugin.JRE_BASE_IMAGE` and `NATIVE_BASE_IMAGE`, the Maven parent's `<image>` elements, and `example-flashcard/Dockerfile`, and each pair moves together.
   A new JDK line is a decision about the Java release (`notes/decisions.md`, decision 79), so report one and do not move it.
7. **Verify.**
   - `./gradlew build`, which compiles every module and runs every test, the Gradle plugin's included build too.
   - `../gradlew build` in `benchmark/`, a build of its own, when anything under `benchmark/` moved.
   - `npm run docs` when Antora or a manual attribute moved.
   - When `spider-silk-maven-parent/pom.xml` moved, `./gradlew :spider-silk-maven-parent:publishToMavenLocal`, then `mvn -U dependency:resolve-plugins` in a scratch project whose parent is it, which proves every plugin coordinate exists.
   A failure is reported with its output; a move that cannot pass is reverted and reported as held back.
8. **Stop and report.**
   Do not commit: AGENTS.md has each commit wait for its own instruction.
   The report lists what moved (from → to), what was held back and why, the base images that have a newer line, and the build result.

## When the script needs a change

- A new copy of a version outside a build file (a Java constant, an Antora attribute) is a new entry in its `MIRRORS` list; the script warns when an entry no longer matches.
- A new build format is a new pattern in `scan`.
- The script needs nothing beyond JDK 25 and follows the repository's no-reflection rule like the rest of the code.
