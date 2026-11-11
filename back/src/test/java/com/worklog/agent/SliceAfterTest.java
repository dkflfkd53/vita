package com.worklog.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.worklog.agent.AgentStore.EventRow;
import com.worklog.agent.AgentStore.ToolCallRow;
import com.worklog.agent.AgentStore.UsageSplitRow;
import com.worklog.agent.ObserverCalc.Data;
import com.worklog.query.Rows.EditRow;
import com.worklog.query.Rows.PromptRow;
import com.worklog.query.Rows.SessionDayRow;

/** 개선점 찾기는 세션마다 본 시각 뒤에 생긴 기록만 다시 본다 — 이어서 쓴 세션은 이어진 부분만 */
class SliceAfterTest {

    private final ObserverCalc calc = new ObserverCalc(null, null, null, null, null);

    @Test
    void keepsOnlyRecordsAfterEachSessionsMark() {
        Data d = new Data(Map.of(), Map.of(),
                List.of(new SessionDayRow("a", "2026-11-09", "2026-11-09T01:00:00Z", "2026-11-09T05:00:00Z", 600, 2, 0, null),
                        new SessionDayRow("a", "2026-11-11", "2026-11-11T01:00:00Z", "2026-11-11T02:00:00Z", 300, 1, 0, null),
                        new SessionDayRow("b", "2026-11-10", "2026-11-10T01:00:00Z", "2026-11-10T02:00:00Z", 300, 1, 0, null)),
                List.of(new PromptRow("p1", "a", "2026-11-09T01:00:00Z", "2026-11-09", "처음 요청"),
                        new PromptRow("p2", "a", "2026-11-11T01:30:00Z", "2026-11-11", "이어서 요청"),
                        new PromptRow("p3", "b", "2026-11-10T01:30:00Z", "2026-11-10", "새 세션 요청")),
                List.<EditRow>of(),
                List.of(new ToolCallRow("t1", "a", "2026-11-09", "2026-11-09T01:10:00Z", null, "Bash", "ls", "error", "x", false),
                        new ToolCallRow("t2", "a", "2026-11-11", "2026-11-11T01:40:00Z", null, "Bash", "ls", "error", "x", false)),
                List.<EventRow>of(),
                List.of(new UsageSplitRow("a", "2026-11-09", "m", false, 1, 1, 0, 0, 0),
                        new UsageSplitRow("a", "2026-11-11", "m", false, 1, 1, 0, 0, 0)));
        // a 는 11/10 에 이미 봤고, b 는 처음
        Instant markA = Instant.parse("2026-11-10T00:00:00Z");
        Instant floor = Instant.parse("2026-11-01T00:00:00Z");
        Data out = calc.sliceAfter(d, sid -> "a".equals(sid) ? markA : floor);

        assertThat(out.prompts()).extracting(PromptRow::uuid).containsExactly("p2", "p3");
        assertThat(out.tools()).extracting(ToolCallRow::id).containsExactly("t2");
        assertThat(out.days()).extracting(SessionDayRow::day).containsExactly("2026-11-11", "2026-11-10");
        assertThat(out.usage()).extracting(UsageSplitRow::day).containsExactly("2026-11-11");
        assertThat(calc.activeSessions(out)).containsExactlyInAnyOrder("a", "b");
    }
}
