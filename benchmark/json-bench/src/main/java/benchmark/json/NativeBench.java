package benchmark.json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.alibaba.fastjson2.JSON;

/**
 * A few of {@link JsonBench}'s cases with no JMH around them, for a GraalVM
 * native image, where JMH cannot run: it forks a JVM for each case. Each case
 * runs for a warm-up of three seconds and ten measured rounds of one second,
 * and reports the median round as microseconds an operation. The same main
 * runs on the JVM, so the two are compared under the same harness.
 *
 * <pre>{@code
 * GRAALVM_HOME=... json-bench/native-bench.sh # builds json-bench/build/native-bench
 * taskset -c 0-3 json-bench/build/native-bench [case-regex]
 * }</pre>
 */
public final class NativeBench {

    private static long sink;

    private NativeBench() {
    }

    public static void main(String[] args) {
        String only = args.length > 0 ? args[0] : ".*";
        byte[] list = JsonBench.ITEMS_GENERATED.toJsonBytes(JsonBench.ITEMS);
        byte[] korean = JsonBench.ITEMS_GENERATED.toJsonBytes(JsonBench.KOREAN_ITEMS);
        byte[] places = JsonBench.PLACES_GENERATED.toJsonBytes(JsonBench.PLACES);
        if (!JsonBench.ITEMS_GENERATED.fromJsonBytes(list).equals(JsonBench.ITEMS)
                || !JsonBench.ITEMS_GENERATED.fromJsonBytes(korean).equals(JsonBench.KOREAN_ITEMS)
                || !JsonBench.PLACES_GENERATED.fromJsonBytes(places).equals(JsonBench.PLACES)) {
            throw new IllegalStateException("The codec read a document as other records");
        }
        boolean fastjson2 = true;
        try {
            if (!JSON.parseArray(list, Item.class).equals(JsonBench.ITEMS)
                    || !JSON.parseArray(korean, Item.class).equals(JsonBench.KOREAN_ITEMS)
                    || !JSON.parseArray(places, Place.class).equals(JsonBench.PLACES)
                    || !JSON.parseArray(JSON.toJSONBytes(JsonBench.ITEMS), Item.class).equals(JsonBench.ITEMS)) {
                throw new IllegalStateException("fastjson2 read or wrote a document as other records");
            }
        } catch (RuntimeException e) {
            System.out.println("fastjson2 skipped: " + e);
            fastjson2 = false;
        }

        Map<String, Supplier<Object>> cases = new LinkedHashMap<>();
        cases.put("writeList_generated", () -> JsonBench.ITEMS_GENERATED.toJsonBytes(JsonBench.ITEMS));
        cases.put("writeList_fastjson2", () -> JSON.toJSONBytes(JsonBench.ITEMS));
        cases.put("writeKoreanList_generated", () -> JsonBench.ITEMS_GENERATED.toJsonBytes(JsonBench.KOREAN_ITEMS));
        cases.put("writeKoreanList_fastjson2", () -> JSON.toJSONBytes(JsonBench.KOREAN_ITEMS));
        cases.put("writePlaces_generated", () -> JsonBench.PLACES_GENERATED.toJsonBytes(JsonBench.PLACES));
        cases.put("writePlaces_fastjson2", () -> JSON.toJSONBytes(JsonBench.PLACES));
        cases.put("readList_generated", () -> JsonBench.ITEMS_GENERATED.fromJsonBytes(list));
        cases.put("readList_fastjson2", () -> JSON.parseArray(list, Item.class));
        cases.put("readKoreanList_generated", () -> JsonBench.ITEMS_GENERATED.fromJsonBytes(korean));
        cases.put("readKoreanList_fastjson2", () -> JSON.parseArray(korean, Item.class));
        cases.put("readPlaces_generated", () -> JsonBench.PLACES_GENERATED.fromJsonBytes(places));
        cases.put("readPlaces_fastjson2", () -> JSON.parseArray(places, Place.class));

        System.out.printf("%-28s %10s %10s %10s%n", "case", "median", "min", "max");
        for (Map.Entry<String, Supplier<Object>> c : cases.entrySet()) {
            if (c.getKey().matches(only) && (fastjson2 || !c.getKey().endsWith("_fastjson2"))) {
                double[] rounds = measure(c.getValue());
                System.out.printf("%-28s %10.3f %10.3f %10.3f us/op%n", c.getKey(), rounds[rounds.length / 2],
                        rounds[0], rounds[rounds.length - 1]);
            }
        }
        if (sink == 42) {
            System.out.println(); // keeps every result in use
        }
    }

    /**
     * The microseconds an operation took in each measured round, sorted.
     * {@code -Dnativebench.quick=true} cuts each round a thousandfold, for the
     * run under the tracing agent that only has to see every path once.
     */
    private static double[] measure(Supplier<Object> operation) {
        long second = Boolean.getBoolean("nativebench.quick") ? 1_000_000L : 1_000_000_000L;
        run(operation, 3 * second);
        List<Double> rounds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rounds.add(run(operation, second));
        }
        double[] sorted = rounds.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        return sorted;
    }

    /** A number read from the result, cheap to take, so that the result is used without hashing it whole. */
    private static long used(Object result) {
        if (result instanceof byte[] bytes) {
            return bytes.length + bytes[bytes.length / 2];
        }
        List<?> list = (List<?>) result;
        return list.size() + (list.get(list.size() / 2) == null ? 1 : 0);
    }

    /** Runs the operation for about that many nanoseconds and answers the microseconds one took. */
    private static double run(Supplier<Object> operation, long nanos) {
        long start = System.nanoTime();
        long end = start + nanos;
        long count = 0;
        long now;
        do {
            for (int i = 0; i < 100; i++) {
                sink += used(operation.get());
            }
            count += 100;
            now = System.nanoTime();
        } while (now < end);
        return (now - start) / 1000.0 / count;
    }
}
