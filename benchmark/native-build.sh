#!/usr/bin/env bash
# Builds both native images from a clean project, BUILD_ROUNDS times each, and
# writes native-build.csv into the result directory given as the argument.
#
# The rounds alternate between the applications. Each build cleans its own
# project first, so it compiles the application, runs Spring's AOT processing
# where there is one, and runs native-image; the Spider Silk modules it depends
# on are already compiled and are not counted. The Gradle daemon is started
# beforehand, so its startup is not counted either. Each binary is copied into
# build/native, where the targets in targets.sh find it.
#
# With ORACLE_GRAALVM_HOME naming an Oracle GraalVM JDK, each round also builds
# each application with it twice: once with its defaults (-oracle), and once
# with profile-guided optimization (-pgo). The profile comes from an
# instrumented image (-instrumented), built once before the rounds and run
# under load by pgo-profile.sh.
set -euo pipefail
cd "$(dirname "$0")"

OUT=${1:?usage: native-build.sh <result directory>}
BUILD_ROUNDS=${BUILD_ROUNDS:-3}
: "${GRAALVM_HOME:?GRAALVM_HOME must name a GraalVM JDK}"
ORACLE_GRAALVM_HOME=${ORACLE_GRAALVM_HOME:-}
APPS="spider-silk-app spring-mvc-app"

mkdir -p "$OUT/logs" build/native
echo "round,app,build_seconds,native_image_seconds,native_image_peak_rss_gib,binary_mb" > "$OUT/native-build.csv"
../gradlew -q help > /dev/null

# Builds an application from a clean project with the GraalVM given, passes
# the remaining arguments to native-image, and copies the binary to
# build/native/<name>: round app name graalvm [native-image arguments]
build() {
    local round=$1 app=$2 name=$3 graalvm=$4
    shift 4
    echo "native build $round: $name"
    local log="$OUT/logs/native-build-$round-$name.log"
    ../gradlew -q ":$app:clean"
    local start
    start=$(date +%s%N)
    GRAALVM_HOME=$graalvm ../gradlew --console=plain ":$app:nativeCompile" -PnativeImageArgs="$*" > "$log" 2>&1
    local seconds native_seconds peak_rss binary_mb
    seconds=$(awk -v ns="$(( $(date +%s%N) - start ))" 'BEGIN { printf "%.1f", ns / 1e9 }')
    # native-image's own summary: "Finished generating 'x' in 1m 2s." and "Peak RSS: 4.17GiB".
    native_seconds=$(sed -n "s/.*Finished generating '[^']*' in \\(.*\\)\\./\\1/p" "$log" \
        | awk '{ s = 0; for (i = 1; i <= NF; i++) { v = $i + 0; s += ($i ~ /m$/) ? v * 60 : v } print s }')
    peak_rss=$(grep -o 'Peak RSS: [0-9.]*Gi\?B' "$log" | grep -o '[0-9.]*' | tail -1)
    cp "$app/build/native/nativeCompile/$app" "build/native/$name"
    binary_mb=$(awk -v b="$(stat -c %s "build/native/$name")" 'BEGIN { printf "%.1f", b / 1048576 }')
    echo "$round,$name,$seconds,$native_seconds,$peak_rss,$binary_mb" >> "$OUT/native-build.csv"
}

if [ -n "$ORACLE_GRAALVM_HOME" ]; then
    for app in $APPS; do
        build 1 "$app" "$app-instrumented" "$ORACLE_GRAALVM_HOME" --pgo-instrument
        ./pgo-profile.sh "$app" "build/pgo/$app" "$OUT/logs"
    done
fi

for round in $(seq "$BUILD_ROUNDS"); do
    for app in $APPS; do
        build "$round" "$app" "$app" "$GRAALVM_HOME"
        if [ -n "$ORACLE_GRAALVM_HOME" ]; then
            build "$round" "$app" "$app-oracle" "$ORACLE_GRAALVM_HOME"
            profiles=$(ls "$PWD/build/pgo/$app"/*.iprof | paste -sd,)
            build "$round" "$app" "$app-pgo" "$ORACLE_GRAALVM_HOME" "--pgo=$profiles"
        fi
    done
done
