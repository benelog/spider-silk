#!/usr/bin/env bash
# Runs every case against every target and writes results/<timestamp>/.
#
# startup.sh times each target's startup first, on an idle machine. Then each
# case gets a fresh process: start it, warm it up, measure once, record its
# memory, stop it. A round runs every case once, and the rounds interleave the
# targets, so a machine that heats up or slows down halfway spreads the drift
# over all of them instead of the last one. summarize.sh reports the medians.
#
# Every setting below and in targets.sh is an environment variable, for instance
#   ROUNDS=1 WARMUP=5s DURATION=10s ./run.sh
# ONLY is an extended regular expression over "target|case" that keeps the
# cases it matches, for instance ONLY='json' or ONLY='spring-mvc|text'.
#
# The GraalVM native images join the run when GRAALVM_HOME names a GraalVM
# JDK: native-build.sh builds them first, timing each build, and the -native
# targets are measured beside the JVM ones.
set -euo pipefail
cd "$(dirname "$0")"

source ./targets.sh

WRK=${WRK:-wrk}
CLIENT_CPUS=${CLIENT_CPUS:-4-7}
THREADS=${THREADS:-4}
CONNECTIONS=${CONNECTIONS:-64}
WARMUP=${WARMUP:-20s}
DURATION=${DURATION:-30s}
ROUNDS=${ROUNDS:-3}
ONLY=${ONLY:-}

command -v "$WRK" > /dev/null || { echo "wrk not found: install it, or point WRK at it" >&2; exit 1; }
if curl -s -o /dev/null "http://127.0.0.1:$PORT/"; then
    echo "port $PORT is already in use" >&2
    exit 1
fi

OUT=results/$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT/logs" "$OUT/responses"
./environment.sh > "$OUT/environment.txt"

# The native builds clean each project first, so installDist comes after them.
if [ -n "$GRAALVM_HOME" ]; then
    ./native-build.sh "$OUT"
    # The builds keep every core busy for minutes; let the machine cool down
    # so the first case does not run on a hot, throttled CPU.
    sleep "${COOLDOWN:-60}"
else
    echo "GRAALVM_HOME is not set: skipping the native images"
fi
../gradlew -q installDist
./startup.sh "$OUT"
{
    echo "SERVER_CPUS=$SERVER_CPUS CLIENT_CPUS=$CLIENT_CPUS THREADS=$THREADS CONNECTIONS=$CONNECTIONS"
    echo "WARMUP=$WARMUP DURATION=$DURATION ROUNDS=$ROUNDS JVM_OPTS=$JVM_OPTS NATIVE_OPTS=$NATIVE_OPTS"
} > "$OUT/settings.txt"
echo "round,target,case,requests_per_sec,p50_ms,p90_ms,p99_ms,max_ms,errors,bytes_per_response,server_cpu_pct,rss_loaded_mb" > "$OUT/results.csv"

# User plus system time the process has used, in clock ticks.
cpu_ticks() {
    awk '{ print $14 + $15 }' "/proc/$1/stat"
}

trap stop_server EXIT

for round in $(seq "$ROUNDS"); do
    while IFS='|' read -r target case command path script; do
        [ -z "$target" ] && continue
        [[ "$target|$case" =~ $ONLY ]] || continue
        included "$target" || continue
        url="http://127.0.0.1:$PORT$path"
        script=${script:-report.lua}
        echo "round $round: $target $case"

        start_server "$command" "$OUT/logs/$round-$target-$case.log"
        if [ "$script" = report.lua ]; then
            curl -s -D - "$url" > "$OUT/responses/$target-$case.txt"
        else
            curl -s -D - -H 'Content-Type: application/json' --data-binary @items.json "$url" > "$OUT/responses/$target-$case.txt"
        fi

        taskset -c "$CLIENT_CPUS" "$WRK" -t"$THREADS" -c"$CONNECTIONS" -d"$WARMUP" -s "$script" "$url" > /dev/null
        ticks=$(cpu_ticks "$PID")
        seconds_start=$(date +%s%N)
        result=$(taskset -c "$CLIENT_CPUS" "$WRK" -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" -s "$script" "$url" \
            | tee "$OUT/logs/$round-$target-$case.wrk" | awk '/^RESULT/ { $1 = ""; print }')
        # 400 means four cores busy for the whole measurement.
        server_cpu=$(awk -v t="$(( $(cpu_ticks "$PID") - ticks ))" -v hz="$(getconf CLK_TCK)" \
            -v ns="$(( $(date +%s%N) - seconds_start ))" 'BEGIN { printf "%.0f", t / hz / (ns / 1e9) * 100 }')
        rss_loaded=$(rss_mb "$PID")
        stop_server

        read -r rps p50 p90 p99 max errors bytes <<< "$result"
        echo "$round,$target,$case,$rps,$p50,$p90,$p99,$max,$errors,$bytes,$server_cpu,$rss_loaded" >> "$OUT/results.csv"
    done <<< "$CASES"
done

./summarize.sh "$OUT" | tee "$OUT/summary.txt"
