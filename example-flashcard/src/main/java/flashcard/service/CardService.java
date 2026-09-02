package flashcard.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
                    Card.create(deckId, text, meaning, LocalDateTime.now(ZoneId.systemDefault())));
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

    /** One card of an import, before it has an id. */
    public record CardDraft(String text, String meaning, String tags) {
    }

    /**
     * A bulk import read as it arrives: the drafts are a lazy stream over the
     * request body, so a file of a hundred thousand cards is never a list.
     *
     * <p>The whole import is one transaction, which is what makes a malformed
     * line safe: the exception it throws leaves this method, the transaction
     * rolls back, and the client is told which line rather than being left with
     * a deck half filled.
     */
    public int importCards(Long deckId, Stream<CardDraft> drafts) {
        return tx.write(() -> {
            int imported = 0;
            Iterator<CardDraft> remaining = drafts.iterator();
            while (remaining.hasNext()) {
                CardDraft draft = remaining.next();
                Card card = cardRepository.insert(
                        Card.create(deckId, draft.text(), draft.meaning(), LocalDateTime.now(ZoneId.systemDefault())));
                attachTags(card.id(), draft.tags());
                imported++;
            }
            return imported;
        });
    }

    /**
     * Every card of a deck, handed over one at a time rather than returned as a
     * list — the read side of the same idea, for an export that streams.
     * Tags are left out: this is the bulk shape, and joining them per row is the
     * N+1 query {@link #cardsWithTags} exists to avoid.
     */
    public void eachCard(Long deckId, Consumer<Card> handler) {
        tx.readVoid(() -> cardRepository.eachByDeckId(deckId, handler));
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
