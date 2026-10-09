#!/usr/bin/env bash
# Reduces a result directory to medians: one line per target and case for the
# load, one per target for startup, and one per application for the native build.
# Throughput and each latency percentile are medians taken separately.
set -euo pipefail
dir=${1:?usage: summarize.sh results/<timestamp>}

median() {
    sort -n | awk '{ v[NR] = $1 } END { print (NR % 2 ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2) }'
}
# The values of a named column, in the rows whose given columns match: column csv name [col value]...
column() {
    local csv=$1 name=$2
    shift 2
    awk -F, -v name="$name" -v filter="$*" '
        NR == 1 { for (i = 1; i <= NF; i++) index_of[$i] = i; n = split(filter, f, " "); next }
        { for (i = 1; i < n; i += 2) if ($index_of[f[i]] != f[i + 1]) next; print $index_of[name] }' "$csv"
}
distinct() { # csv name
    column "$1" "$2" | awk '!seen[$0]++'
}

csv=$dir/results.csv
printf '%-20s %-26s %10s %8s %8s %8s %7s %8s %6s %12s\n' \
    case target 'req/s' 'p50 ms' 'p90 ms' 'p99 ms' errors bytes 'cpu %' 'RSS (MB)'
for case in $(distinct "$csv" case); do
    for target in $(column "$csv" target case "$case" | awk '!seen[$0]++'); do
        v() { column "$csv" "$1" case "$case" target "$target"; }
        printf '%-20s %-26s %10.0f %8.2f %8.2f %8.2f %7d %8.0f %6.0f %12.0f\n' "$case" "$target" \
            "$(v requests_per_sec | median)" "$(v p50_ms | median)" "$(v p90_ms | median)" "$(v p99_ms | median)" \
            "$(v errors | awk '{ s += $1 } END { print s }')" "$(v bytes_per_response | median)" \
            "$(v server_cpu_pct | median)" "$(v rss_loaded_mb | median)"
    done
done

csv=$dir/startup.csv
if [ -f "$csv" ]; then
    echo
    printf '%-26s %7s %8s %8s %8s %10s\n' target starts 'min ms' 'median' 'max ms' 'RSS (MB)'
    for target in $(distinct "$csv" target); do
        v() { column "$csv" "$1" target "$target"; }
        printf '%-26s %7d %8d %8.0f %8d %10.0f\n' "$target" \
            "$(v startup_ms | wc -l)" "$(v startup_ms | sort -n | head -1)" "$(v startup_ms | median)" \
            "$(v startup_ms | sort -n | tail -1)" "$(v rss_mb | median)"
    done
fi

csv=$dir/native-build.csv
if [ -f "$csv" ]; then
    echo
    printf '%-26s %7s %10s %16s %16s %12s\n' app builds 'median s' 'native-image s' 'peak RSS (GiB)' 'binary (MB)'
    for app in $(distinct "$csv" app); do
        v() { column "$csv" "$1" app "$app"; }
        printf '%-26s %7d %10.1f %16.1f %16.2f %12.1f\n' "$app" \
            "$(v build_seconds | wc -l)" "$(v build_seconds | median)" "$(v native_image_seconds | median)" \
            "$(v native_image_peak_rss_gib | median)" "$(v binary_mb | median)"
    done
fi
