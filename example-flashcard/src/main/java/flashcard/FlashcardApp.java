package flashcard;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.benelog.spidersilk.App;

/**
 * Application startup: FlashcardContext builds the whole App, and this class
 * only starts it.
 *
 * <p>{@code --dev} switches the templates to the source tree, so an edited .jte
 * file shows up on browser refresh; {@link FlashcardContext} has the details.
 */
public class FlashcardApp {

    private static final Logger logger = LoggerFactory.getLogger(FlashcardApp.class);

    public static void main(String[] args) {
        boolean devMode = Arrays.asList(args).contains("--dev");
        App app = new FlashcardContext(FlashcardDatabase.file(), devMode)
                .start(8080);
        logger.info("Flashcard: http://localhost:{}", app.port());
        app.join();
    }
}
