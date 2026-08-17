package flashcard.domain;

import java.util.List;

/** For the deck detail screen: a card plus the tag names attached to it. */
public record CardWithTags(Card card, List<String> tags) {

    public String tagsAsText() {
        return String.join(", ", tags);
    }
}
