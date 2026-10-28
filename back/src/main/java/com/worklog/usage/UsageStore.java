package com.worklog.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.worklog.usage.UsageParser.UsageRow;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class UsageStore {

    private final JdbcClient jdbc;

    public Optional<Long> offset(String path) {
        return jdbc.sql("SELECT read_offset FROM ingest_usage_file WHERE path = ?").param(path).query(Long.class).optional();
    }

    /** 같은 응답이 여러 줄이면 필드별 최댓값(마지막 줄이 최종 집계) */
    @Transactional
    public void save(List<UsageRow> rows, ZoneId zone, String path, long offset) {
        for (UsageRow r : rows) {
            jdbc.sql("""
                    INSERT INTO usage_event (message_id, session_id, day, model, input_tokens, output_tokens, cache_read,
                                             cache_write_5m, cache_write_1h, sidechain)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(message_id) DO UPDATE SET
                        input_tokens = MAX(usage_event.input_tokens, excluded.input_tokens),
                        output_tokens = MAX(usage_event.output_tokens, excluded.output_tokens),
                        cache_read = MAX(usage_event.cache_read, excluded.cache_read),
                        cache_write_5m = MAX(usage_event.cache_write_5m, excluded.cache_write_5m),
                        cache_write_1h = MAX(usage_event.cache_write_1h, excluded.cache_write_1h)
                    """).params(r.messageId(), r.sessionId(), LocalDate.ofInstant(r.at(), zone).toString(), r.model(),
                    r.input(), r.output(), r.cacheRead(), r.cacheWrite5m(), r.cacheWrite1h(), r.sidechain() ? 1 : 0)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO ingest_usage_file (path, read_offset, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET read_offset = excluded.read_offset, updated_at = excluded.updated_at
                """).params(path, offset, Instant.now().toString()).update();
    }

    public List<AggRow> aggregate(String from, String to) {
        return jdbc.sql("""
                SELECT session_id, day, model, COUNT(*), SUM(input_tokens), SUM(output_tokens), SUM(cache_read),
                       SUM(cache_write_5m), SUM(cache_write_1h)
                FROM usage_event WHERE day BETWEEN ? AND ?
                GROUP BY session_id, day, model
                """).params(from, to).query((rs, i) -> new AggRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9))).list();
    }

    public record AggRow(String sessionId, String day, String model, int responses, long input, long output,
                         long cacheRead, long cacheWrite5m, long cacheWrite1h) {
    }
}
