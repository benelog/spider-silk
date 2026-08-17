package flashcard.service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import flashcard.domain.Card;
import flashcard.domain.CardTagName;
import flashcard.domain.CardWithTags;
import flashcard.domain.Tag;
import flashcard.repository.CardRepository;
import flashcard.repository.TagRepository;

public class CardService {

    private final CardRepository cardRepository;
    private final TagRepository tagRepository;
    private final Transactions tx;

    public CardService(CardRepository cardRepository, TagRepository tagRepository,
                       Transactions tx) {
        this.cardRepository = cardRepository;
        this.tagRepository = tagRepository;
        this.tx = tx;
    }

    public Card addCard(Long deckId, String text, String meaning, String tagsText) {
        return tx.write(() -> {
            Card card = cardRepository.insert(
                    Card.create(deckId, text, meaning, LocalDateTime.now()));
            attachTags(card.id(), tagsText);
            return card;
        });
    }

    public void editCard(Long cardId, String text, String meaning, String tagsText) {
        tx.writeVoid(() -> {
            Card card = getCard(cardId);
            cardRepository.update(card.edit(text, meaning));
            tagRepository.detachAll(cardId);
            attachTags(cardId, tagsText);
        });
    }

    public void deleteCard(Long cardId) {
        tx.writeVoid(() -> cardRepository.deleteById(cardId));
    }

    public Card getCard(Long cardId) {
        return cardRepository.findById(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));
    }

    /**
     * Loads cards and tags with one query each and zips them. No N+1 queries.
     * The read transaction keeps the two queries on one snapshot.
     */
    public List<CardWithTags> cardsWithTags(Long deckId) {
        return tx.read(() -> {
            List<Card> cards = cardRepository.findByDeckId(deckId);
            Map<Long, List<String>> tagsByCard = tagRepository.findTagNamesByDeckId(deckId)
                    .stream()
                    .collect(Collectors.groupingBy(CardTagName::cardId,
                            Collectors.mapping(CardTagName::tagName, Collectors.toList())));

            return cards.stream()
                    .map(card -> new CardWithTags(card,
                            tagsByCard.getOrDefault(card.id(), List.of())))
                    .toList();
        });
    }

    public List<String> tagsOf(Long cardId) {
        return tagRepository.findTagNamesByCardId(cardId);
    }

    /** Joins the caller's transaction. (Same effect as propagation REQUIRED.) */
    void attachTags(Long cardId, String tagsText) {
        parseTags(tagsText).forEach(tagName -> {
            Tag tag = tagRepository.findByName(tagName)
                    .orElseGet(() -> tagRepository.insert(Tag.create(tagName)));
            tagRepository.attach(cardId, tag.id());
        });
    }

    static List<String> parseTags(String tagsText) {
        if (tagsText == null || tagsText.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tagsText.split("[,;]"))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .distinct()
                .toList();
    }
}
