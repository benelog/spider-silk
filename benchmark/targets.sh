# shellcheck shell=bash
# The targets and cases run.sh and startup.sh share, and the helpers that start
# and stop a server. Sourced, not run.

JAVA=${JAVA:-java}
SERVER_CPUS=${SERVER_CPUS:-0-3}
JVM_OPTS=${JVM_OPTS:--Xms1g -Xmx1g}
# A native image reads the same heap flags at startup.
NATIVE_OPTS=${NATIVE_OPTS:-$JVM_OPTS}
GRAALVM_HOME=${GRAALVM_HOME:-}
PORT=8080

SPIDER_SILK_JVM="$JAVA $JVM_OPTS -cp spider-silk-app/build/install/spider-silk-app/lib/* benchmark.spidersilk.SpiderSilkBenchmark"
SPRING_MVC_JVM="$JAVA $JVM_OPTS -cp spring-mvc-app/build/install/spring-mvc-app/lib/* benchmark.springmvc.SpringMvcBenchmark"
SPIDER_SILK_NATIVE="spider-silk-app/build/native/nativeCompile/spider-silk-app $NATIVE_OPTS"
SPRING_MVC_NATIVE="spring-mvc-app/build/native/nativeCompile/spring-mvc-app $NATIVE_OPTS"

# target | case | command | path | wrk script (report.lua when left out)
# Spider Silk's arguments pick the server and the template engine. A case
# under post-items.lua sends items.json as a POST body; run.sh fetches its
# sample response with the same body.
# startup.sh starts each target with the command of its text case.
CASES="
spider-silk-jetty|text|$SPIDER_SILK_JVM jetty jte|/text
spider-silk-tomcat|text|$SPIDER_SILK_JVM tomcat jte|/text
spring-mvc|text|$SPRING_MVC_JVM|/text
spider-silk-jetty-native|text|$SPIDER_SILK_NATIVE jetty jte|/text
spring-mvc-native|text|$SPRING_MVC_NATIVE|/text
spider-silk-jetty|json|$SPIDER_SILK_JVM jetty jte|/json
spider-silk-tomcat|json|$SPIDER_SILK_JVM tomcat jte|/json
spring-mvc|json|$SPRING_MVC_JVM|/json
spider-silk-jetty-native|json|$SPIDER_SILK_NATIVE jetty jte|/json
spring-mvc-native|json|$SPRING_MVC_NATIVE|/json
spider-silk-jetty|json-list|$SPIDER_SILK_JVM jetty jte|/items
spider-silk-tomcat|json-list|$SPIDER_SILK_JVM tomcat jte|/items
spring-mvc|json-list|$SPRING_MVC_JVM|/items
spider-silk-jetty-native|json-list|$SPIDER_SILK_NATIVE jetty jte|/items
spring-mvc-native|json-list|$SPRING_MVC_NATIVE|/items
spider-silk-jetty|json-post|$SPIDER_SILK_JVM jetty jte|/items|post-items.lua
spider-silk-tomcat|json-post|$SPIDER_SILK_JVM tomcat jte|/items|post-items.lua
spring-mvc|json-post|$SPRING_MVC_JVM|/items|post-items.lua
spider-silk-jetty-native|json-post|$SPIDER_SILK_NATIVE jetty jte|/items|post-items.lua
spring-mvc-native|json-post|$SPRING_MVC_NATIVE|/items|post-items.lua
spider-silk-jetty|template-jte|$SPIDER_SILK_JVM jetty jte|/fortunes
spider-silk-tomcat|template-jte|$SPIDER_SILK_JVM tomcat jte|/fortunes
spider-silk-jetty-native|template-jte|$SPIDER_SILK_NATIVE jetty jte|/fortunes
spider-silk-jetty|template-thymeleaf|$SPIDER_SILK_JVM jetty thymeleaf|/fortunes
spider-silk-tomcat|template-thymeleaf|$SPIDER_SILK_JVM tomcat thymeleaf|/fortunes
spring-mvc|template-thymeleaf|$SPRING_MVC_JVM|/fortunes
spider-silk-jetty-native|template-thymeleaf|$SPIDER_SILK_NATIVE jetty thymeleaf|/fortunes
spring-mvc-native|template-thymeleaf|$SPRING_MVC_NATIVE|/fortunes
"

# Whether a target is measured: the native ones need GRAALVM_HOME.
included() {
    [[ -n "$GRAALVM_HOME" || "$1" != *-native ]]
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
