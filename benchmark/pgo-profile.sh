#!/usr/bin/env bash
# Runs an application's instrumented native image under the load of its
# -native-pgo cases and leaves the profiles in the directory given, one .iprof
# file per command, for native-build.sh to build the optimized image from.
#
# The instrumented image counts what the code does as it runs and writes
# default.iprof into the working directory when it exits. Each distinct
# command, Spider Silk's template engine for one, gets a process of its own,
# and every case it serves gets PROFILE_DURATION of wrk.
set -euo pipefail
cd "$(dirname "$0")"
source ./targets.sh

APP=${1:?usage: pgo-profile.sh <app> <profile directory> <log directory>}
PROFILES=${2:?usage: pgo-profile.sh <app> <profile directory> <log directory>}
LOGS=${3:?usage: pgo-profile.sh <app> <profile directory> <log directory>}
PROFILE_DURATION=${PROFILE_DURATION:-10s}

mkdir -p "$PROFILES" "$LOGS"
rm -f "$PROFILES"/*.iprof default.iprof
trap stop_server EXIT

# The -native-pgo rows of this application, with the instrumented image in
# place of the optimized one.
rows=$(while IFS='|' read -r target case command path script; do
    [[ "$command" == "build/native/$APP-pgo "* ]] || continue
    echo "${command/$APP-pgo /$APP-instrumented }|$path|${script:-report.lua}|$case"
done <<< "$CASES")

n=0
while read -r command; do
    n=$((n + 1))
    echo "pgo profile $n: $command"
    start_server "$command" "$LOGS/pgo-profile-$APP-$n.log"
    while IFS='|' read -r row_command path script case; do
        [ "$row_command" = "$command" ] || continue
        echo "  $case"
        taskset -c "$CLIENT_CPUS" "$WRK" -t"$THREADS" -c"$CONNECTIONS" -d"$PROFILE_DURATION" -s "$script" \
            "http://127.0.0.1:$PORT$path" > "$LOGS/pgo-profile-$APP-$n-$case.wrk"
    done <<< "$rows"
    stop_server
    [ -s default.iprof ] || { echo "the instrumented image wrote no default.iprof" >&2; exit 1; }
    mv default.iprof "$PROFILES/$n.iprof"
done < <(cut -d'|' -f1 <<< "$rows" | awk '!seen[$0]++')
