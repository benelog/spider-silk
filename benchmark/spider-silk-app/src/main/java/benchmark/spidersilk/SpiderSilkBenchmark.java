package benchmark.spidersilk;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import java.util.List;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.JteTemplates;
import net.benelog.spidersilk.Model;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonWriter;
import net.benelog.spidersilk.thymeleaf.ThymeleafTemplates;
import net.benelog.spidersilk.tomcat.TomcatServer;

/**
 * The Spider Silk side of the benchmark.
 *
 * <p>The first argument picks the server ({@code jetty} or {@code tomcat}) and
 * the second the template engine ({@code jte} or {@code thymeleaf}).
 */
public final class SpiderSilkBenchmark {

    static final JsonWriter<Message> MESSAGE = message -> Json.object()
            .put("message", message.message());

    static final JsonWriter<Item> ITEM = item -> Json.object()
            .put("id", item.id())
            .put("name", item.name())
            .put("description", item.description())
            .put("quantity", item.quantity())
            .put("available", item.available());

    static final JsonWriter<List<Item>> ITEMS = JsonWriter.list(ITEM);

    private SpiderSilkBenchmark() {
    }

    public static void main(String[] args) {
        String server = args.length > 0 ? args[0] : "jetty";
        String templates = args.length > 1 ? args[1] : "jte";

        App app = new App();
        app.get("/text", req -> WebResponse.text("Hello, World!"));
        app.get("/json", req -> WebResponse.json(new Message("Hello, World!"), MESSAGE));
        app.get("/items", req -> WebResponse.json(Data.ITEMS, ITEMS));
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
