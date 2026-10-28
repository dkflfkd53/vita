package com.worklog.collect;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 번의 수집 동안 세션 하나에 쌓이는 변경분. DB 의 기존 상태(마지막 이벤트 시각 등)에서 이어 붙인다.
 * 작업 시간은 이벤트 간격이 idleGap 이하면 이어서 일한 것으로 보고, 뒤쪽 이벤트가 속한 날짜에 더한다.
 */
final class SessionAccumulator {

    final String id;
    String customTitle;
    String aiTitle;
    String firstPrompt;
    String cwd;
    String project;
    String gitBranch;
    String continuedIn;
    Instant firstAt;
    Instant lastAt;
    Instant lastEventAt;

    final Map<LocalDate, DayDelta> days = new HashMap<>();
    final List<PromptRow> prompts = new ArrayList<>();
    final List<EditRow> edits = new ArrayList<>();

    SessionAccumulator(String id) {
        this.id = id;
    }

    void event(Instant at, ZoneId zone, Duration idleGap) {
        LocalDate day = LocalDate.ofInstant(at, zone);
        DayDelta delta = days.computeIfAbsent(day, d -> new DayDelta());
        if (lastEventAt != null && at.isAfter(lastEventAt)) {
            long gap = Duration.between(lastEventAt, at).toSeconds();
            if (gap <= idleGap.toSeconds()) {
                delta.activeSec += gap;
            }
        }
        delta.firstAt = min(delta.firstAt, at);
        delta.lastAt = max(delta.lastAt, at);
        firstAt = min(firstAt, at);
        lastAt = max(lastAt, at);
        lastEventAt = max(lastEventAt, at);
    }

    private static Instant min(Instant a, Instant b) {
        return a == null || b.isBefore(a) ? b : a;
    }

    private static Instant max(Instant a, Instant b) {
        return a == null || b.isAfter(a) ? b : a;
    }

    static final class DayDelta {
        Instant firstAt;
        Instant lastAt;
        long activeSec;
    }

    record PromptRow(String uuid, Instant at, LocalDate day, String text) {
    }

    record EditRow(String toolUseId, Instant at, LocalDate day, String tool, String path, String repo) {
    }
}
