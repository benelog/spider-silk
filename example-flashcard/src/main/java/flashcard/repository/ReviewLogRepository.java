package flashcard.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import flashcard.domain.DailyStat;
import flashcard.domain.ReviewLog;

public class ReviewLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReviewLogRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    public void insert(ReviewLog log) {
        jdbc.update("""
                insert into review_log (card_id, correct, retry_round, reviewed_at, study_date)
                values (:cardId, :correct, :retryRound, :reviewedAt, :studyDate)
                """,
                new MapSqlParameterSource()
                        .addValue("cardId", log.cardId())
                        .addValue("correct", log.correct())
                        .addValue("retryRound", log.retryRound())
                        .addValue("reviewedAt", log.reviewedAt())
                        .addValue("studyDate", log.studyDate()));
    }

    /** Correct/wrong counts per day. Feeds the bar chart on the stats screen. */
    public List<DailyStat> findDailyStats(LocalDate since) {
        return jdbc.query("""
                select study_date,
                       sum(case when correct then 1 else 0 end)     as correct_count,
                       sum(case when not correct then 1 else 0 end) as wrong_count
                from review_log
                where study_date >= :since
                group by study_date
                order by study_date
                """, Map.of("since", since), DataClassRowMapper.newInstance(DailyStat.class));
    }

    /** Study dates, newest first. Used to compute the study streak. */
    public List<LocalDate> findStudyDates() {
        return jdbc.queryForList("""
                select distinct study_date
                from review_log
                order by study_date desc
                """, Map.of(), LocalDate.class);
    }

    public long countAll() {
        return jdbc.queryForObject("select count(*) from review_log", Map.of(), Long.class);
    }

    public long countCorrect() {
        return jdbc.queryForObject("select count(*) from review_log where correct",
                Map.of(), Long.class);
    }
}
