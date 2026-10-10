# Benchmark

This directory measures Spider Silk against Spring MVC on the same machine, with the same JVM and the same load.
The manual's [Performance Benchmark](../manual/modules/ROOT/pages/benchmark.adoc) appendix reports a run of it and explains the method.

## What is here

- `spider-silk-app/`: the Spider Silk application, on Jetty or Tomcat, rendering with jte or Thymeleaf, with its JSON through the codecs generated from its records.
- `spring-mvc-app/`: the Spring Boot application, with Spring MVC, Jackson, and Thymeleaf on Spring Boot's defaults.
- `json-bench/`: a JMH microbenchmark of the JSON serializer and parser alone, on the documents the HTTP cases send and on a document of decimals, against Jackson and fastjson2.
- `items.json`: the 100 records the `json-list` case answers with, which the `json-post` case sends as a body through `post-items.lua`.
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

## The JSON microbenchmark

`json-bench` measures the serializer and the parser with no HTTP around them: the tree, a hand-written writer, the generated codec, Jackson, and fastjson2, each writing or reading the same 100 records and the same 27-byte object.
The `Places` cases write and read 100 places with a latitude, a longitude, and a rating each, which measures the numbers with a fraction that the records have none of.
The `PlainList` and `KoreanList` cases write the same records through the generated codec and fastjson2, once with nothing to escape and once with Korean strings, which tells apart the paths a string can take.

```bash
../gradlew -p json-bench installDist
taskset -c 0-3 json-bench/build/install/json-bench/bin/json-bench -jvmArgs "-Xms1g -Xmx1g" -prof gc
```

Each case runs in two forks of five warm-up and five measured iterations of a second, pinned to the server CPUs, and `-prof gc` reports the bytes allocated per operation beside the time.
