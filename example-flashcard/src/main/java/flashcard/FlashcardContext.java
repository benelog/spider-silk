package flashcard;

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
import flashcard.web.DeckController;
import flashcard.web.HomeAction;
import flashcard.web.SmartDeckController;
import flashcard.web.StatsAction;
import flashcard.web.StudyController;

/**
 * A hand-written counterpart of Spring's ApplicationContext: the constructor
 * wires the whole object graph by calling constructors directly, without a
 * DI container. The dependency graph is visible right here in the code.
 */
public class FlashcardContext {

    private final HomeAction homeAction;
    private final DeckController deckController;
    private final StudyController studyController;
    private final SmartDeckController smartDeckController;
    private final StatsAction statsAction;
    private final ApiController apiController;

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

        this.homeAction = new HomeAction(deckService, studyService, smartDeckService);
        this.deckController = new DeckController(deckService, cardService);
        this.studyController = new StudyController(studyService, smartDeckService);
        this.smartDeckController = new SmartDeckController(smartDeckService);
        this.statsAction = new StatsAction(statsService);
        this.apiController = new ApiController(deckService, cardService);
    }

    public HomeAction homeAction() {
        return homeAction;
    }

    public DeckController deckController() {
        return deckController;
    }

    public StudyController studyController() {
        return studyController;
    }

    public SmartDeckController smartDeckController() {
        return smartDeckController;
    }

    public StatsAction statsAction() {
        return statsAction;
    }

    public ApiController apiController() {
        return apiController;
    }
}
