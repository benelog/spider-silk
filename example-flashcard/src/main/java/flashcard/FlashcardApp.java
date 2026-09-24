package flashcard;

import java.util.Arrays;

import net.benelog.spidersilk.App;

/**
 * Application startup: FlashcardContext builds the whole App, and this class
 * only starts it.
 *
 * <p>{@code --dev} switches the templates to the source tree, so an edited .jte
 * file shows up on browser refresh; {@link FlashcardContext} has the details.
 */
public class FlashcardApp {

    public static void main(String[] args) {
        boolean devMode = Arrays.asList(args).contains("--dev");
        App app = new FlashcardContext(FlashcardDatabase.file(), devMode)
                .start(8080);
        System.out.println("Flashcard: http://localhost:" + app.port());
        app.join();
    }
}
