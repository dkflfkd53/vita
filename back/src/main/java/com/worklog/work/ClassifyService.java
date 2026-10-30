package com.worklog.work;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.worklog.config.RoutineSessions;
import com.worklog.config.WorklogProperties;
import com.worklog.query.QueryStore;
import com.worklog.query.Rows.PromptRow;
import com.worklog.query.Views.SessionDayRef;
import com.worklog.query.WorkQueryService;
import com.worklog.report.Period;
import com.worklog.summary.ClaudeCli;
import com.worklog.summary.JobService;
import com.worklog.summary.SummaryService;
import com.worklog.work.WorkStore.Link;
import com.worklog.work.WorkStore.WorkItem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 세션×날짜를 그 주 계획 업무에 AI 로 붙인다. 사용자가 정한 것(manual)·세션 고정(pin)은 건드리지 않는다.
 * 하루 요약이 있으면 요약을, 없으면(오늘) 요청 원문 앞뒤 몇 개를 근거로 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassifyService {

    private static final int PROMPTS_PER_DAY = 6;
    private static final int PROMPT_CHARS = 160;

    private final WorklogProperties props;
    private final RoutineSessions routines;
    private final WorkStore store;
    private final WorkQueryService query;
    private final QueryStore queryStore;
    private final ClaudeCli claude;
    private final JobService jobs;
    private final SubtypeService subtypes;

    /** 새로 생긴 미분류 작업이 있을 때만 이번 주를 분류한다 */
    @Scheduled(initialDelay = 600_000, fixedDelayString = "${worklog.classify-interval}")
    public void scheduled() {
        if (!props.autoClassify()) {
            return;
        }
        String week = Period.weekKey(LocalDate.now(props.zone()));
        if (!candidates(week, false).isEmpty() && !store.itemsInWeek(week).isEmpty()) {
            submit(week, false);
        }
    }

    /** redo = AI 가 이미 붙인 것도 다시 판단(업무를 새로 추가했을 때) */
    public JobService.Job submit(String weekKey, boolean redo) {
        Period.of("week", weekKey);
        return jobs.submit("classify:" + weekKey, weekKey + " 업무 자동 분류", progress -> {
            int n = classify(weekKey, redo);
            // 세부 분류가 빈 이 주 할 일도 같이 채운다(실패해도 세션 분류 결과는 남긴다)
            int filled = 0;
            try {
                filled = subtypes.fill(store.itemsInWeek(weekKey));
            } catch (IOException | RuntimeException e) {
                log.warn("{} 세부 분류 채우기 실패: {}", weekKey, e.getMessage());
            }
            String sub = filled > 0 ? " · 세부 분류 " + filled + "개" : "";
            return (n < 0 ? "이 주 계획 업무가 없어 건너뜀" : n + "건 분류") + sub;
        });
    }

    /** @return 분류한 건수, 계획 업무가 없으면 -1 */
    public int classify(String weekKey, boolean redo) throws IOException {
        List<WorkItem> items = store.itemsInWeek(weekKey);
        if (items.isEmpty()) {
            return -1;
        }
        List<SessionDayRef> targets = candidates(weekKey, redo);
        if (targets.isEmpty()) {
            return 0;
        }
        Map<String, List<PromptRow>> prompts = promptsOf(targets);
        StringBuilder sb = new StringBuilder();
        sb.append("[계획 업무]\n");
        for (WorkItem w : items) {
            sb.append("- id ").append(w.id()).append(" | ").append(w.category() == null ? "-" : w.category())
                    .append(" | ").append(w.title());
            if (w.memo() != null) {
                sb.append(" | 메모: ").append(cut(w.memo()));
            }
            sb.append('\n');
        }
        sb.append("\n[작업]\n");
        for (SessionDayRef r : targets) {
            sb.append("- key: ").append(r.key()).append(" | 세션: ").append(r.title())
                    .append(" | 프로젝트: ").append(r.project() == null ? "-" : r.project()).append('\n');
            if (r.summary() != null) {
                sb.append("  요약: ").append(r.summary()).append('\n');
            } else {
                List<PromptRow> ps = prompts.getOrDefault(r.key(), List.of());
                ps.stream().limit(PROMPTS_PER_DAY / 2).forEach(p -> sb.append("  요청: ").append(cut(p.text())).append('\n'));
                ps.stream().skip(Math.max(PROMPTS_PER_DAY / 2, ps.size() - PROMPTS_PER_DAY / 2))
                        .forEach(p -> sb.append("  요청: ").append(cut(p.text())).append('\n'));
            }
        }

        String prompt = """
                너는 개발자의 주간 업무 계획과 실제 Claude Code 작업 세션을 연결하는 담당이다.
                [작업] 하나하나가 [계획 업무] 중 어느 것에 해당하는지 고른다.
                - 작업의 세션 제목·요약·요청이 그 업무와 같은 일을 다루면 그 업무 id.
                - 관련이 뚜렷하지 않거나 개인 일·잡무면 null. 억지로 붙이지 않는다.
                - 작업 하나에 업무 하나만.
                출력은 JSON 하나만, 코드펜스나 설명 없이:
                {"links": [{"key": "<작업 key 그대로>", "workItemId": <업무 id 숫자 또는 null>}]}

                %s""".formatted(sb);
        JsonNode json = SummaryService.parseJson(claude.ask(prompt, props.classifyModel()));
        Set<Long> ids = items.stream().map(WorkItem::id).collect(Collectors.toSet());
        Map<String, SessionDayRef> byKey = targets.stream().collect(Collectors.toMap(SessionDayRef::key, r -> r));
        int saved = 0;
        for (JsonNode l : json.path("links")) {
            SessionDayRef r = byKey.get(l.path("key").asText());
            if (r == null) {
                continue;
            }
            JsonNode idNode = l.path("workItemId");
            Long id = idNode.isNumber() && ids.contains(idNode.asLong()) ? idNode.asLong() : null;
            store.saveAutoLink(r.sessionId(), r.day(), id);
            saved++;
        }
        log.info("{} 업무 분류 {}건 (대상 {})", weekKey, saved, targets.size());
        return saved;
    }

    List<SessionDayRef> candidates(String weekKey, boolean redo) {
        Period week = Period.of("week", weekKey);
        LocalDate today = LocalDate.now(props.zone());
        LocalDate to = week.to().isAfter(today) ? today : week.to();
        if (week.from().isAfter(to)) {
            return List.of();
        }
        Map<String, Link> links = new HashMap<>();
        store.links(week.from().toString(), to.toString()).forEach(l -> links.put(l.key(), l));
        Map<String, Long> pins = store.pins();
        Map<String, String> pinFrom = store.pinStarts();
        return query.sessionDayRefs(week.from(), to).stream()
                .filter(r -> r.prompts() > 0 || r.summary() != null)
                .filter(r -> !routines.isRoutine(r.title())) // 배포 같은 반복 업무는 분류하지 않는다
                // 고정된 세션도 고정 시작일 전 작업은 분류 대상
                .filter(r -> !pins.containsKey(r.sessionId())
                        || (pinFrom.get(r.sessionId()) != null && r.day().compareTo(pinFrom.get(r.sessionId())) < 0))
                .filter(r -> {
                    Link l = links.get(r.key());
                    return l == null || (redo && "auto".equals(l.source()));
                })
                .toList();
    }

    private Map<String, List<PromptRow>> promptsOf(List<SessionDayRef> refs) {
        Set<String> ids = refs.stream().map(SessionDayRef::sessionId).collect(Collectors.toSet());
        String from = refs.stream().map(SessionDayRef::day).min(String::compareTo).orElseThrow();
        String to = refs.stream().map(SessionDayRef::day).max(String::compareTo).orElseThrow();
        return queryStore.prompts(ids, from, to).stream()
                .collect(Collectors.groupingBy(p -> p.sessionId() + "|" + p.day()));
    }

    private static String cut(String text) {
        String s = text.replaceAll("\\s+", " ").strip();
        return s.length() > PROMPT_CHARS ? s.substring(0, PROMPT_CHARS) + "…" : s;
    }
}
