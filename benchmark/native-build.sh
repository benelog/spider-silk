#!/usr/bin/env bash
# Builds both native images from a clean project, BUILD_ROUNDS times each, and
# writes native-build.csv into the result directory given as the argument.
#
# The rounds alternate between the applications. Each build cleans its own
# project first, so it compiles the application, runs Spring's AOT processing
# where there is one, and runs native-image; the Spider Silk modules it depends
# on are already compiled and are not counted. The Gradle daemon is started
# beforehand, so its startup is not counted either.
set -euo pipefail
cd "$(dirname "$0")"

OUT=${1:?usage: native-build.sh <result directory>}
BUILD_ROUNDS=${BUILD_ROUNDS:-3}
: "${GRAALVM_HOME:?GRAALVM_HOME must name a GraalVM JDK}"
export GRAALVM_HOME

mkdir -p "$OUT/logs"
echo "round,app,build_seconds,native_image_seconds,native_image_peak_rss_gib,binary_mb" > "$OUT/native-build.csv"
../gradlew -q help > /dev/null

for round in $(seq "$BUILD_ROUNDS"); do
    for app in spider-silk-app spring-mvc-app; do
        echo "native build $round: $app"
        log="$OUT/logs/native-build-$round-$app.log"
        ../gradlew -q ":$app:clean"
        start=$(date +%s%N)
        ../gradlew --console=plain ":$app:nativeCompile" > "$log" 2>&1
        seconds=$(awk -v ns="$(( $(date +%s%N) - start ))" 'BEGIN { printf "%.1f", ns / 1e9 }')
        # native-image's own summary: "Finished generating 'x' in 1m 2s." and "Peak RSS: 4.17GiB".
        native_seconds=$(sed -n "s/.*Finished generating '[^']*' in \\(.*\\)\\./\\1/p" "$log" \
            | awk '{ s = 0; for (i = 1; i <= NF; i++) { v = $i + 0; s += ($i ~ /m$/) ? v * 60 : v } print s }')
        peak_rss=$(grep -o 'Peak RSS: [0-9.]*Gi\?B' "$log" | grep -o '[0-9.]*' | tail -1)
        binary_mb=$(awk -v b="$(stat -c %s "$app/build/native/nativeCompile/$app")" 'BEGIN { printf "%.1f", b / 1048576 }')
        echo "$round,$app,$seconds,$native_seconds,$peak_rss,$binary_mb" >> "$OUT/native-build.csv"
    done
done
