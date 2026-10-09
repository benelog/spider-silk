# Benchmark

This directory measures Spider Silk against Spring MVC on the same machine, with the same JVM and the same load.
The manual's [Performance Benchmark](../manual/modules/ROOT/pages/benchmark.adoc) appendix reports a run of it and explains the method.

## What is here

- `spider-silk-app/`: the Spider Silk application, on Jetty or Tomcat, rendering with jte or Thymeleaf.
- `spring-mvc-app/`: the Spring Boot application, with Spring MVC, Jackson, and Thymeleaf on Spring Boot's defaults.
- `run.sh`: runs the whole benchmark through the scripts below, starts a fresh process per case, warms it up, measures it with [wrk](https://github.com/wg/wrk), and writes `results/<timestamp>/`.
- `native-build.sh`: builds both applications as GraalVM native images from a clean project and times each build.
- `startup.sh`: times each target's startup on an idle machine.
- `targets.sh`: the targets and cases the scripts share.
- `summarize.sh`: reduces a result directory to medians.
- `environment.sh`: prints the machine, the OS, the JVM, and wrk, into each result directory.

This is a Gradle build of its own, so `./gradlew build` at the root does not download Spring Boot.
It includes the root build, so the Spider Silk side runs the modules of this working tree rather than a published release.

## Running it

It needs Linux (`taskset` and `/proc`), Java 21 or later, curl, and wrk.
The native images also need a GraalVM JDK named by `GRAALVM_HOME`; without it, `run.sh` measures the JVM cases only.

```bash
sudo apt install wrk # or: brew install wrk
sdk install java 25.2.4-graalce # for the native images
GRAALVM_HOME=~/.sdkman/candidates/java/25.2.4-graalce benchmark/run.sh
```

The default run takes about 75 minutes: three native builds of each application, ten timed starts of each target, then 23 cases in three rounds, each with a fresh process, 20 seconds of warm-up, and 30 seconds of measurement.
The server runs on CPUs 0-3 and wrk on CPUs 4-7, so a machine with fewer than eight CPUs needs `SERVER_CPUS` and `CLIENT_CPUS` set.
Every setting is an environment variable, listed at the top of `run.sh`:

```bash
ROUNDS=1 BUILD_ROUNDS=1 STARTS=3 WARMUP=5s DURATION=10s benchmark/run.sh # a quick look
ONLY='json' benchmark/run.sh # the JSON cases only
SERVER_CPUS=0-1 CLIENT_CPUS=2-3 THREADS=2 benchmark/run.sh # a four-CPU machine
```

Close other heavy programs first: the numbers are only comparable within one run on a quiet machine.
