package com.worklog.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.worklog.agent.AgentParser.Event;
import com.worklog.agent.AgentParser.Item;
import com.worklog.agent.AgentParser.ToolEnd;
import com.worklog.agent.AgentParser.ToolStart;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class AgentStore {

    private final JdbcClient jdbc;

    public Optional<Long> offset(String path) {
        return jdbc.sql("SELECT read_offset FROM ingest_agent_file WHERE path = ?").param(path).query(Long.class).optional();
    }

    @Transactional
    public void save(List<Item> items, ZoneId zone, String path, long offset) {
        for (Item it : items) {
            switch (it) {
                case ToolStart s -> jdbc.sql("""
                        INSERT INTO agent_tool_call (tool_use_id, session_id, day, started_at, tool, target, sidechain)
                        VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(tool_use_id) DO NOTHING
                        """).params(s.id(), s.sessionId(), LocalDate.ofInstant(s.at(), zone).toString(), s.at().toString(),
                        s.tool(), s.target(), s.sidechain() ? 1 : 0).update();
                // 시작 줄이 없는 결과(다른 파일에 있던 호출)는 버린다
                case ToolEnd e -> jdbc.sql("UPDATE agent_tool_call SET ended_at = ?, status = ?, error_text = ? WHERE tool_use_id = ?")
                        .params(e.at().toString(), e.status(), e.errorText(), e.id()).update();
                case Event e -> jdbc.sql("""
                        INSERT INTO agent_event (session_id, at, day, kind, value_ms, detail) VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(session_id, at, kind) DO NOTHING
                        """).params(e.sessionId(), e.at().toString(), LocalDate.ofInstant(e.at(), zone).toString(), e.kind(),
                        e.valueMs(), e.detail()).update();
            }
        }
        jdbc.sql("""
                INSERT INTO ingest_agent_file (path, read_offset, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET read_offset = excluded.read_offset, updated_at = excluded.updated_at
                """).params(path, offset, Instant.now().toString()).update();
    }

    public List<ToolCallRow> toolCalls(String from, String to) {
        return jdbc.sql("""
                SELECT tool_use_id, session_id, day, started_at, ended_at, tool, target, status, error_text, sidechain
                FROM agent_tool_call WHERE day BETWEEN ? AND ?
                """).params(from, to).query((rs, i) -> new ToolCallRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getInt(10) == 1)).list();
    }

    public List<EventRow> events(String from, String to) {
        return jdbc.sql("SELECT session_id, at, day, kind, value_ms, detail FROM agent_event WHERE day BETWEEN ? AND ? ORDER BY at")
                .params(from, to).query((rs, i) -> new EventRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5) instanceof Number n ? n.longValue() : null, rs.getString(6))).list();
    }

    /** 사용량을 세션·모델·본체/서브에이전트로 — 비용은 서비스에서 단가표로 */
    public List<UsageSplitRow> usage(String from, String to) {
        return jdbc.sql("""
                SELECT session_id, day, model, sidechain, SUM(input_tokens), SUM(output_tokens), SUM(cache_read),
                       SUM(cache_write_5m), SUM(cache_write_1h)
                FROM usage_event WHERE day BETWEEN ? AND ? GROUP BY session_id, day, model, sidechain
                """).params(from, to).query((rs, i) -> new UsageSplitRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getInt(4) == 1, rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9))).list();
    }

    /** 에이전트 기록이 있는 가장 이른 날 — 이보다 앞은 원본이 이미 지워져 도구·턴 지표가 없다 */
    public Optional<String> firstDay() {
        return jdbc.sql("SELECT MIN(day) FROM agent_tool_call").query(String.class).optional();
    }

    public record ToolCallRow(String id, String sessionId, String day, String startedAt, String endedAt, String tool,
                              String target, String status, String errorText, boolean sidechain) {
    }

    public record EventRow(String sessionId, String at, String day, String kind, Long valueMs, String detail) {
    }

    public record UsageSplitRow(String sessionId, String day, String model, boolean sidechain, long input, long output,
                                long cacheRead, long cacheWrite5m, long cacheWrite1h) {
    }
}
