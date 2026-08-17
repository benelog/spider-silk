package flashcard;

import java.util.List;

import javax.sql.DataSource;

import flashcard.repository.CardRepository;
import flashcard.repository.DeckRepository;
import flashcard.repository.ReviewLogRepository;
import flashcard.repository.ReviewStateRepository;
import flashcard.repository.SmartDeckRepository;
import flashcard.repository.TagRepository;
import flashcard.service.CardService;
import flashcard.service.DeckService;
import flashcard.service.SmartDeckService;
import flashcard.service.StatsService;
import flashcard.service.StudyService;
import flashcard.service.Transactions;
import flashcard.web.ApiController;
import flashcard.web.Controller;
import flashcard.web.DeckController;
import flashcard.web.HomeController;
import flashcard.web.RoutesController;
import flashcard.web.SmartDeckController;
import flashcard.web.StatsController;
import flashcard.web.StudyController;

/**
 * A hand-written counterpart of Spring's ApplicationContext: the constructor
 * wires the whole object graph by calling constructors directly, without a
 * DI container. The dependency graph is visible right here in the code.
 */
public class FlashcardContext {

    private final List<Controller> controllers;

    public FlashcardContext(DataSource dataSource) {
        Transactions tx = new Transactions(dataSource);

        CardRepository cardRepository = new CardRepository(dataSource);
        DeckRepository deckRepository = new DeckRepository(dataSource);
        TagRepository tagRepository = new TagRepository(dataSource);
        ReviewStateRepository reviewStateRepository = new ReviewStateRepository(dataSource);
        ReviewLogRepository reviewLogRepository = new ReviewLogRepository(dataSource);
        SmartDeckRepository smartDeckRepository = new SmartDeckRepository(dataSource);

        CardService cardService = new CardService(cardRepository, tagRepository, tx);
        DeckService deckService = new DeckService(deckRepository, cardRepository,
                cardService, tx);
        SmartDeckService smartDeckService = new SmartDeckService(smartDeckRepository,
                cardRepository, tx);
        StatsService statsService = new StatsService(reviewLogRepository, deckRepository, tx);
        StudyService studyService = new StudyService(cardRepository, reviewStateRepository,
                reviewLogRepository, deckService, smartDeckService, tx);

        this.controllers = List.of(
                new HomeController(deckService, studyService, smartDeckService),
                new DeckController(deckService, cardService),
                new StudyController(studyService, smartDeckService),
                new SmartDeckController(smartDeckService),
                new StatsController(statsService),
                new ApiController(deckService, cardService),
                new RoutesController());
    }

    /** All controllers in the context, like getBeansOfType(Controller.class). */
    public List<Controller> controllers() {
        return controllers;
    }
}
