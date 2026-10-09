package benchmark.spidersilk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The values every handler answers with, built once so that a request measures the framework, not the data. */
final class Data {

    /** 100 records, about 9 KB of JSON. */
    static final List<Item> ITEMS = items();

    /** The TechEmpower fortunes, sorted by message: one row to escape and one in Japanese. */
    static final List<Fortune> FORTUNES = fortunes();

    private Data() {
    }

    private static List<Item> items() {
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            items.add(new Item(i, "item-" + i, "The \"description\" of item " + i, i * 7 % 50, i % 3 != 0));
        }
        return List.copyOf(items);
    }

    private static List<Fortune> fortunes() {
        List<Fortune> fortunes = new ArrayList<>(List.of(
                new Fortune(1, "fortune: No such file or directory"),
                new Fortune(2, "A computer scientist is someone who fixes things that aren't broken."),
                new Fortune(3, "After enough decimal places, nobody gives a damn."),
                new Fortune(4, "A bad random number generator: 1, 1, 1, 1, 1, 4.33e+67, 1, 1, 1"),
                new Fortune(5, "A computer program does what you tell it to do, not what you want it to do."),
                new Fortune(6, "Emacs is a nice operating system, but I prefer UNIX. — Tom Christaensen"),
                new Fortune(7, "Any program that runs right is obsolete."),
                new Fortune(8, "A list is only as strong as its weakest link. — Donald Knuth"),
                new Fortune(9, "Feature: A bug with seniority."),
                new Fortune(10, "Computers make very fast, very accurate mistakes."),
                new Fortune(11, "<script>alert(\"This should not be displayed in a browser alert box.\");</script>"),
                new Fortune(12, "フレームワークのベンチマーク")));
        fortunes.sort(Comparator.comparing(Fortune::message));
        return List.copyOf(fortunes);
    }
}
