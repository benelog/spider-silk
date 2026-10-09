package benchmark.spidersilk;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import java.util.List;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.JteTemplates;
import net.benelog.spidersilk.Model;
import net.benelog.spidersilk.WebResponse;
import net.benelog.silkjson.JsonCodec;
import net.benelog.silkjson.JsonWriter;
import net.benelog.spidersilk.thymeleaf.ThymeleafTemplates;
import net.benelog.spidersilk.tomcat.TomcatServer;

/**
 * The Spider Silk side of the benchmark.
 *
 * <p>The first argument picks the server ({@code jetty} or {@code tomcat}) and
 * the second the template engine ({@code jte} or {@code thymeleaf}). The JSON
 * goes through the codecs generated from {@link Item} and {@link Message};
 * {@code ITEM_WRITER} is the same mapping written by hand, kept for the
 * {@code json-bench} microbenchmark's comparison.
 */
public final class SpiderSilkBenchmark {

    static final JsonWriter<Item> ITEM_WRITER = (item, out) -> out.object()
            .put("id", item.id())
            .put("name", item.name())
            .put("description", item.description())
            .put("quantity", item.quantity())
            .put("available", item.available())
            .end();

    static final JsonCodec<List<Item>> ITEMS = JsonCodec.list(ItemJson.CODEC);

    private SpiderSilkBenchmark() {
    }

    public static void main(String[] args) {
        String server = args.length > 0 ? args[0] : "jetty";
        String templates = args.length > 1 ? args[1] : "jte";

        App app = new App();
        app.get("/text", req -> WebResponse.text("Hello, World!"));
        app.get("/json", req -> WebResponse.json(new Message("Hello, World!"), MessageJson.CODEC));
        app.get("/items", req -> WebResponse.json(Data.ITEMS, ITEMS));
        app.post("/items", req -> WebResponse.text(String.valueOf(req.bodyJson(ITEMS).size())));
        app.get("/fortunes", req -> WebResponse.template("fortunes", Model.of("fortunes", Data.FORTUNES)));

        if (templates.equals("thymeleaf")) {
            app.templates(new ThymeleafTemplates("thymeleaf")); // classpath:/thymeleaf/fortunes.html
        } else {
            app.templates(new JteTemplates(TemplateEngine.createPrecompiled(ContentType.Html)));
        }
        if (server.equals("tomcat")) {
            app.server((a, port) -> new TomcatServer(a).port(port));
        }
        app.start(8080);
    }
}
