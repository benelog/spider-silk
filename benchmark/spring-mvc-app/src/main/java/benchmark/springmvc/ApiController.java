package benchmark.springmvc;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Text and JSON, the latter written by Jackson. */
@RestController
public class ApiController {

    @GetMapping(path = "/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public String text() {
        return "Hello, World!";
    }

    @GetMapping("/json")
    public Message json() {
        return new Message("Hello, World!");
    }

    @GetMapping("/items")
    public List<Item> items() {
        return Data.ITEMS;
    }
}
