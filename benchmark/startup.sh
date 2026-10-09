#!/usr/bin/env bash
# Starts every target STARTS times on an idle machine and writes startup.csv
# into the result directory given as the argument: the time from before the
# process starts to its first 200, and its resident memory at that moment.
#
# Startup is timed here rather than in run.sh's load cases, because a process
# started right after the previous one stopped under full load starts slower,
# by more than a third for Spider Silk on Jetty. PAUSE seconds of quiet go
# before each start.
set -euo pipefail
cd "$(dirname "$0")"
source ./targets.sh

OUT=${1:?usage: startup.sh <result directory>}
STARTS=${STARTS:-10}
PAUSE=${PAUSE:-3}

mkdir -p "$OUT/logs"
echo "round,target,startup_ms,rss_mb" > "$OUT/startup.csv"
trap stop_server EXIT

for round in $(seq "$STARTS"); do
    while IFS='|' read -r target case command path; do
        [[ -z "$target" || "$case" != text ]] && continue
        included "$target" || continue
        echo "start $round: $target"
        sleep "$PAUSE"
        start_server "$command" "$OUT/logs/startup-$round-$target.log"
        rss=$(rss_mb "$PID")
        stop_server
        echo "$round,$target,$STARTUP_MS,$rss" >> "$OUT/startup.csv"
    done <<< "$CASES"
done
