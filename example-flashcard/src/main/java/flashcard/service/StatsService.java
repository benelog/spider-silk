package flashcard.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import flashcard.domain.DailyStat;
import flashcard.domain.DeckStat;
import flashcard.repository.DeckRepository;
import flashcard.repository.ReviewLogRepository;

public class StatsService {

    public static final int CHART_DAYS = 30;

    private final ReviewLogRepository reviewLogRepository;
    private final DeckRepository deckRepository;
    private final Transactions tx;

    public StatsService(ReviewLogRepository reviewLogRepository, DeckRepository deckRepository,
                        Transactions tx) {
        this.reviewLogRepository = reviewLogRepository;
        this.deckRepository = deckRepository;
        this.tx = tx;
    }

    public record StatsView(int streakDays, long totalReviews, int accuracyPercent,
                            List<DailyStat> dailyStats, List<DeckStat> deckStats,
                            long maxDailyTotal) {
    }

    /** The read transaction keeps the aggregate queries on one snapshot. */
    public StatsView overview() {
        return tx.read(() -> {
            LocalDate today = LocalDate.now();
            List<DailyStat> daily = padDays(
                    reviewLogRepository.findDailyStats(today.minusDays(CHART_DAYS - 1)), today);

            long total = reviewLogRepository.countAll();
            long correct = reviewLogRepository.countCorrect();
            int accuracy = total == 0 ? 0 : (int) Math.round(correct * 100.0 / total);
            long maxDailyTotal = daily.stream().mapToLong(DailyStat::total).max().orElse(0);

            return new StatsView(streakDays(today), total, accuracy,
                    daily, deckRepository.findAllStats(), maxDailyTotal);
        });
    }

    /** Study streak: consecutive study days counting back from today (or yesterday). */
    int streakDays(LocalDate today) {
        List<LocalDate> dates = reviewLogRepository.findStudyDates();
        if (dates.isEmpty()) {
            return 0;
        }

        LocalDate anchor = dates.getFirst();
        if (!anchor.equals(today) && !anchor.equals(today.minusDays(1))) {
            return 0;   // already rested for more than two days
        }

        int streak = 1;
        for (int i = 1; i < dates.size(); i++) {
            if (dates.get(i).equals(anchor.minusDays(streak))) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    /** Fills days without study with zeros so empty days show up in the chart. */
    private List<DailyStat> padDays(List<DailyStat> stats, LocalDate today) {
        Map<LocalDate, DailyStat> byDate = stats.stream()
                .collect(Collectors.toMap(DailyStat::studyDate, Function.identity()));

        List<DailyStat> padded = new ArrayList<>();
        for (int i = CHART_DAYS - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            padded.add(byDate.getOrDefault(date, new DailyStat(date, 0, 0)));
        }
        return padded;
    }
}
