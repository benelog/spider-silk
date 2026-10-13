# shellcheck shell=bash
# The settings, targets, and cases run.sh, startup.sh, and pgo-profile.sh share,
# and the helpers that start and stop a server. Sourced, not run.

JAVA=${JAVA:-java}
SERVER_CPUS=${SERVER_CPUS:-0-3}
WRK=${WRK:-wrk}
CLIENT_CPUS=${CLIENT_CPUS:-4-7}
THREADS=${THREADS:-4}
CONNECTIONS=${CONNECTIONS:-64}
JVM_OPTS=${JVM_OPTS:--Xms1g -Xmx1g}
# A native image reads the same heap flags at startup.
NATIVE_OPTS=${NATIVE_OPTS:-$JVM_OPTS}
GRAALVM_HOME=${GRAALVM_HOME:-}
# An Oracle GraalVM JDK, for the -native-oracle and -native-pgo targets.
ORACLE_GRAALVM_HOME=${ORACLE_GRAALVM_HOME:-}
PORT=8080

SPIDER_SILK_JVM="$JAVA $JVM_OPTS -cp spider-silk-app/build/install/spider-silk-app/lib/* benchmark.spidersilk.SpiderSilkBenchmark"
SPRING_BOOT_JVM="$JAVA $JVM_OPTS -cp spring-boot-app/build/install/spring-boot-app/lib/* benchmark.springboot.SpringBootBenchmark"
# native-build.sh copies each binary into build/native, out of reach of the
# clean that comes before the next build.
SPIDER_SILK_NATIVE="build/native/spider-silk-app $NATIVE_OPTS"
SPRING_BOOT_NATIVE="build/native/spring-boot-app $NATIVE_OPTS"
SPIDER_SILK_ORACLE="build/native/spider-silk-app-oracle $NATIVE_OPTS"
SPRING_BOOT_ORACLE="build/native/spring-boot-app-oracle $NATIVE_OPTS"
SPIDER_SILK_PGO="build/native/spider-silk-app-pgo $NATIVE_OPTS"
SPRING_BOOT_PGO="build/native/spring-boot-app-pgo $NATIVE_OPTS"

# target | case | command | path | wrk script (report.lua when left out)
# Spider Silk's arguments pick the server and the template engine. A case
# under post-items.lua sends items.json as a POST body; run.sh fetches its
# sample response with the same body.
# startup.sh starts each target with the command of its text case, and
# pgo-profile.sh runs the -native-pgo cases against the instrumented image.
CASES="
spider-silk-jetty|text|$SPIDER_SILK_JVM jetty jte|/text
spider-silk-tomcat|text|$SPIDER_SILK_JVM tomcat jte|/text
spring-boot|text|$SPRING_BOOT_JVM|/text
spider-silk-jetty-native|text|$SPIDER_SILK_NATIVE jetty jte|/text
spider-silk-jetty-native-oracle|text|$SPIDER_SILK_ORACLE jetty jte|/text
spider-silk-jetty-native-pgo|text|$SPIDER_SILK_PGO jetty jte|/text
spring-boot-native|text|$SPRING_BOOT_NATIVE|/text
spring-boot-native-oracle|text|$SPRING_BOOT_ORACLE|/text
spring-boot-native-pgo|text|$SPRING_BOOT_PGO|/text
spider-silk-jetty|json|$SPIDER_SILK_JVM jetty jte|/json
spider-silk-tomcat|json|$SPIDER_SILK_JVM tomcat jte|/json
spring-boot|json|$SPRING_BOOT_JVM|/json
spider-silk-jetty-native|json|$SPIDER_SILK_NATIVE jetty jte|/json
spider-silk-jetty-native-oracle|json|$SPIDER_SILK_ORACLE jetty jte|/json
spider-silk-jetty-native-pgo|json|$SPIDER_SILK_PGO jetty jte|/json
spring-boot-native|json|$SPRING_BOOT_NATIVE|/json
spring-boot-native-oracle|json|$SPRING_BOOT_ORACLE|/json
spring-boot-native-pgo|json|$SPRING_BOOT_PGO|/json
spider-silk-jetty|json-list|$SPIDER_SILK_JVM jetty jte|/items
spider-silk-tomcat|json-list|$SPIDER_SILK_JVM tomcat jte|/items
spring-boot|json-list|$SPRING_BOOT_JVM|/items
spider-silk-jetty-native|json-list|$SPIDER_SILK_NATIVE jetty jte|/items
spider-silk-jetty-native-oracle|json-list|$SPIDER_SILK_ORACLE jetty jte|/items
spider-silk-jetty-native-pgo|json-list|$SPIDER_SILK_PGO jetty jte|/items
spring-boot-native|json-list|$SPRING_BOOT_NATIVE|/items
spring-boot-native-oracle|json-list|$SPRING_BOOT_ORACLE|/items
spring-boot-native-pgo|json-list|$SPRING_BOOT_PGO|/items
spider-silk-jetty|json-post|$SPIDER_SILK_JVM jetty jte|/items|post-items.lua
spider-silk-tomcat|json-post|$SPIDER_SILK_JVM tomcat jte|/items|post-items.lua
spring-boot|json-post|$SPRING_BOOT_JVM|/items|post-items.lua
spider-silk-jetty-native|json-post|$SPIDER_SILK_NATIVE jetty jte|/items|post-items.lua
spider-silk-jetty-native-oracle|json-post|$SPIDER_SILK_ORACLE jetty jte|/items|post-items.lua
spider-silk-jetty-native-pgo|json-post|$SPIDER_SILK_PGO jetty jte|/items|post-items.lua
spring-boot-native|json-post|$SPRING_BOOT_NATIVE|/items|post-items.lua
spring-boot-native-oracle|json-post|$SPRING_BOOT_ORACLE|/items|post-items.lua
spring-boot-native-pgo|json-post|$SPRING_BOOT_PGO|/items|post-items.lua
spider-silk-jetty|template-jte|$SPIDER_SILK_JVM jetty jte|/fortunes
spider-silk-tomcat|template-jte|$SPIDER_SILK_JVM tomcat jte|/fortunes
spider-silk-jetty-native|template-jte|$SPIDER_SILK_NATIVE jetty jte|/fortunes
spider-silk-jetty-native-oracle|template-jte|$SPIDER_SILK_ORACLE jetty jte|/fortunes
spider-silk-jetty-native-pgo|template-jte|$SPIDER_SILK_PGO jetty jte|/fortunes
spider-silk-jetty|template-thymeleaf|$SPIDER_SILK_JVM jetty thymeleaf|/fortunes
spider-silk-tomcat|template-thymeleaf|$SPIDER_SILK_JVM tomcat thymeleaf|/fortunes
spring-boot|template-thymeleaf|$SPRING_BOOT_JVM|/fortunes
spider-silk-jetty-native|template-thymeleaf|$SPIDER_SILK_NATIVE jetty thymeleaf|/fortunes
spider-silk-jetty-native-oracle|template-thymeleaf|$SPIDER_SILK_ORACLE jetty thymeleaf|/fortunes
spider-silk-jetty-native-pgo|template-thymeleaf|$SPIDER_SILK_PGO jetty thymeleaf|/fortunes
spring-boot-native|template-thymeleaf|$SPRING_BOOT_NATIVE|/fortunes
spring-boot-native-oracle|template-thymeleaf|$SPRING_BOOT_ORACLE|/fortunes
spring-boot-native-pgo|template-thymeleaf|$SPRING_BOOT_PGO|/fortunes
"

# Whether a target is measured: the native ones need GRAALVM_HOME, and the
# Oracle GraalVM ones ORACLE_GRAALVM_HOME as well.
included() {
    case $1 in
        *-native) [ -n "$GRAALVM_HOME" ] ;;
        *-native-*) [ -n "$GRAALVM_HOME" ] && [ -n "$ORACLE_GRAALVM_HOME" ] ;;
        *) true ;;
    esac
}

rss_mb() {
    awk '/^VmRSS/ { printf "%.0f", $2 / 1024 }' "/proc/$1/status"
}

# Starts a command on the server CPUs, logging to $2, and waits for the first
# 200 from /text. Sets PID, and STARTUP_MS to the time from before the process
# starts to that answer. The command splits into words, and the classpath's *
# is left for the JVM to expand rather than the shell.
PID=
STARTUP_MS=
start_server() {
    local start
    start=$(date +%s%N)
    set -f
    # shellcheck disable=SC2086
    taskset -c "$SERVER_CPUS" $1 > "$2" 2>&1 &
    PID=$!
    set +f
    until curl -sf -o /dev/null "http://127.0.0.1:$PORT/text"; do
        kill -0 "$PID" 2> /dev/null || { echo "the server exited; see $2" >&2; exit 1; }
        sleep 0.01
    done
    STARTUP_MS=$(( ($(date +%s%N) - start) / 1000000 ))
}

stop_server() {
    if [ -n "$PID" ]; then
        kill "$PID" 2> /dev/null || true
        wait "$PID" 2> /dev/null || true
        PID=
    fi
}
