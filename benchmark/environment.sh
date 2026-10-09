#!/usr/bin/env bash
# Prints the machine, the OS, the JVM, and the load generator a run used.
echo "date: $(date -Iseconds)"
echo "commit: $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- .. || echo ' (with local changes)')"
echo "machine: $(cat /sys/class/dmi/id/sys_vendor /sys/class/dmi/id/product_version 2>/dev/null | paste -sd ' ')"
lscpu | grep -E '^(Model name|CPU\(s\)|Thread\(s\) per core|CPU max MHz|L2 cache|L3 cache):' | sed 's/  */ /g'
for cpu in /sys/devices/system/cpu/cpu[0-9]*; do
    printf '%s max %s MHz\n' "${cpu##*/}" "$(( $(cat "$cpu/cpufreq/cpuinfo_max_freq") / 1000 ))"
done
echo "memory: $(free -h | awk '/^Mem/ { print $2 }')"
echo "os: $(. /etc/os-release && echo "$PRETTY_NAME"), kernel $(uname -r)"
echo "governor: $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null)"
echo "platform profile: $(cat /sys/firmware/acpi/platform_profile 2>/dev/null)"
echo "on AC power: $(cat /sys/class/power_supply/A*/online 2>/dev/null | head -1)"
echo "load average: $(cut -d' ' -f1-3 /proc/loadavg)"
"${JAVA:-java}" -version 2>&1 | sed 's/^/java: /'
"${WRK:-wrk}" --version 2>&1 | head -1 | sed 's/^/wrk: /'
if [ -n "${GRAALVM_HOME:-}" ]; then
    "$GRAALVM_HOME/bin/native-image" --version 2>&1 | sed 's/^/native-image: /'
fi
