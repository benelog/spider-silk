package flashcard.repository;

import org.junit.jupiter.api.Test;

import flashcard.domain.SmartCondition;
import flashcard.domain.SmartDeck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartDeckRepositoryTest extends RepositoryTestSupport {

    private final SmartDeckRepository smartDeckRepository = new SmartDeckRepository(dataSource);

    @Test
    void enumConditionRoundTripsThroughTheVarcharColumn() {
        SmartDeck saved = smartDeckRepository.insert(
                SmartDeck.create("Often missed", SmartCondition.OFTEN_WRONG, null));

        SmartDeck found = smartDeckRepository.findById(saved.id()).orElseThrow();
        assertEquals(SmartCondition.OFTEN_WRONG, found.conditionType());
        assertEquals("Often missed", found.name());
    }

    @Test
    void listsInIdOrderAndDeletes() {
        SmartDeck first = smartDeckRepository.insert(
                SmartDeck.create("Tagged", SmartCondition.TAGGED, "toeic"));
        SmartDeck second = smartDeckRepository.insert(
                SmartDeck.create("Recent", SmartCondition.RECENT, "3"));

        assertEquals(2, smartDeckRepository.findAllOrdered().size());
        assertEquals(first.id(), smartDeckRepository.findAllOrdered().getFirst().id());

        smartDeckRepository.deleteById(first.id());
        smartDeckRepository.deleteById(second.id());
        assertTrue(smartDeckRepository.findAllOrdered().isEmpty());
    }
}
