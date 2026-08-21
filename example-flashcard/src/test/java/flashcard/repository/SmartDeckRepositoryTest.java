package flashcard.repository;

import org.junit.jupiter.api.Test;

import flashcard.domain.SmartCondition;
import flashcard.domain.SmartDeck;

import static org.assertj.core.api.Assertions.assertThat;


class SmartDeckRepositoryTest extends RepositoryTestSupport {

    private final SmartDeckRepository smartDeckRepository = new SmartDeckRepository(dataSource);

    @Test
    void enumConditionRoundTripsThroughTheVarcharColumn() {
        SmartDeck saved = smartDeckRepository.insert(
                SmartDeck.create("Often missed", SmartCondition.OFTEN_WRONG, null));

        SmartDeck found = smartDeckRepository.findById(saved.id()).orElseThrow();
        assertThat(found.conditionType()).isEqualTo(SmartCondition.OFTEN_WRONG);
        assertThat(found.name()).isEqualTo("Often missed");
    }

    @Test
    void listsInIdOrderAndDeletes() {
        SmartDeck first = smartDeckRepository.insert(
                SmartDeck.create("Tagged", SmartCondition.TAGGED, "toeic"));
        SmartDeck second = smartDeckRepository.insert(
                SmartDeck.create("Recent", SmartCondition.RECENT, "3"));

        assertThat(smartDeckRepository.findAllOrdered()).hasSize(2);
        assertThat(smartDeckRepository.findAllOrdered().getFirst().id()).isEqualTo(first.id());

        smartDeckRepository.deleteById(first.id());
        smartDeckRepository.deleteById(second.id());
        assertThat(smartDeckRepository.findAllOrdered()).isEmpty();
    }
}
