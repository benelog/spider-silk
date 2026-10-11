#!/usr/bin/env bash
# Builds json-bench/build/native-bench, NativeBench as a GraalVM native image.
# fastjson2 binds through reflection in a native image, so the reachability
# metadata comes first from a quick run on the JVM under the tracing agent,
# with fastjson2 in the reflection mode it takes in the image.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GRAALVM_HOME:?GRAALVM_HOME must name a GraalVM JDK}"

../gradlew -q -p json-bench installDist
cp=$(ls json-bench/build/install/json-bench/lib/*.jar | tr '\n' ':')
config=json-bench/build/native-config
rm -rf "$config"
"$GRAALVM_HOME/bin/java" -agentlib:native-image-agent=config-output-dir="$config" \
    -Dfastjson2.creator=reflect -Dnativebench.quick=true -cp "$cp" benchmark.json.NativeBench > /dev/null
# fastjson2's own image settings initialize JSONFactory and a few more at build
# time, and the objects they hold drag in more of the package than they list,
# so the image does not build without the whole package there. Built that way,
# fastjson2 keeps the build JVM's byte-array offset for its Unsafe reads, which
# is not the image's, and NativeBench finds it misreading and leaves it out.
"$GRAALVM_HOME/bin/native-image" --no-fallback -H:ConfigurationFileDirectories="$config" \
    --initialize-at-build-time=com.alibaba.fastjson2 \
    -cp "$cp" benchmark.json.NativeBench -o json-bench/build/native-bench
