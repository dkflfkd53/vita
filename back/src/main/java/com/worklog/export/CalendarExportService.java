package com.worklog.export;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.worklog.agent.AgentStore.EventRow;
import com.worklog.agent.AgentStore.ToolCallRow;
import com.worklog.agent.ObserverCalc;
import com.worklog.deploy.DeployService;
import com.worklog.query.Rows.EditRow;
import com.worklog.query.Rows.PromptRow;
import com.worklog.query.TaskCatalog;
import com.worklog.deploy.DeployService.Event;
import com.worklog.query.Views.DayDetail;
import com.worklog.query.Views.DaySession;
import com.worklog.query.Views.DayTask;
import com.worklog.query.WorkQueryService;

import lombok.RequiredArgsConstructor;

/**
 * 세션 캘린더를 .ics 로 — 구글 캘린더 «가져오기»로 별도 캘린더에 넣는 용도.
 * <ul>
 *   <li>세션(작업 묶음): 이어진 날짜만큼 하루 종일 일정 하나(세션 캘린더 막대와 같은 모양), 제목 = 세션 이름</li>
 *   <li>배포(CI/CD): 날짜 × 원천마다 하나, 여러 번이어도 한 번</li>
 * </ul>
 * UID 를 고정해서 다시 가져와도 같은 일정으로 본다. 숨긴 세션·카테고리는 세션 캘린더처럼 빠진다.
 */
@Service
@RequiredArgsConstructor
public class CalendarExportService {

    private static final DateTimeFormatter D = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String APP = "http://127.0.0.1:4100";

    private final WorkQueryService query;
    private final DeployService deploy;
    private final JdbcClient jdbc;
    private final ObserverCalc calc;
    private final TaskCatalog catalog;

    /** 이 간격 이하면 이어서 일한 것(작업 시간 계산과 같은 기준) */
    private static final long GAP_MS = 15 * 60_000L;
    /** 이보다 짧은 덩어리는 질문 하나 하고 끝난 것 — 캘린더를 어지럽혀 뺀다 */
    private static final long MIN_BLOCK_MS = 5 * 60_000L;
    /** 끝나지 않은 도구 호출 등 이상치 상한 */
    private static final long TOOL_CAP_MS = 60 * 60_000L;
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);

    private record Span(long from, long to) {
    }

    private static final class Block {
        long from;
        long to;
        final List<String> prompts = new ArrayList<>();
        final Set<String> files = new LinkedHashSet<>();
    }

    /**
     * 시간대별 — 작업 묶음(세션들)마다 요청·도구 호출·턴·수정 시각을 이어 붙여, 15분 넘게 비면 끊은 실제 일한 덩어리를
     * 시간 지정 일정으로. 5분 미만 덩어리는 뺀다. 배포는 실제 시각에 짧은 일정
     */
    /** 시간대별 일정 하나 — uid 는 덩어리 시작 시각으로 고정(같은 덩어리가 길어져도 같은 일정) */
    public record TimedEvent(String uid, long from, long to, String summary, String description, String url) {
    }

    public String icsTimed(LocalDate from, LocalDate to) {
        StringBuilder out = new StringBuilder();
        head(out, "Vita 시간대");
        String stamp = Instant.now().toString().replaceAll("[-:]", "").replaceAll("\\.\\d+", "");
        for (TimedEvent e : timedEvents(from, to)) {
            timed(out, e.uid(), stamp, e.from(), e.to(), e.summary(), e.description(), e.url());
        }
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    public List<TimedEvent> timedEvents(LocalDate from, LocalDate to) {
        List<TimedEvent> result = new ArrayList<>();
        ObserverCalc.Data d = calc.load(from, to);
        TaskCatalog.Index index = catalog.build();
        Map<String, List<Span>> spans = new LinkedHashMap<>();
        Map<String, List<PromptRow>> prompts = new LinkedHashMap<>();
        Map<String, List<EditRow>> edits = new LinkedHashMap<>();
        java.util.function.Function<String, String> taskOf = sid -> index.taskOfSession().get(sid);
        for (ToolCallRow c : d.tools()) {
            String k = taskOf.apply(c.sessionId());
            Long a = ms(c.startedAt());
            if (k == null || a == null) {
                continue;
            }
            Long b = ms(c.endedAt());
            long end = b == null ? a : Math.min(b, a + TOOL_CAP_MS);
            spans.computeIfAbsent(k, x -> new ArrayList<>()).add(new Span(a, Math.max(a, end)));
        }
        for (PromptRow p : d.prompts()) {
            String k = taskOf.apply(p.sessionId());
            Long a = ms(p.at());
            if (k == null || a == null) {
                continue;
            }
            spans.computeIfAbsent(k, x -> new ArrayList<>()).add(new Span(a, a));
            prompts.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
        }
        for (EventRow e : d.events()) {
            String k = taskOf.apply(e.sessionId());
            Long a = ms(e.at());
            if (k != null && a != null) {
                spans.computeIfAbsent(k, x -> new ArrayList<>()).add(new Span(a, a));
            }
        }
        for (EditRow e : d.edits()) {
            String k = taskOf.apply(e.sessionId());
            Long a = ms(e.at());
            if (k != null && a != null) {
                spans.computeIfAbsent(k, x -> new ArrayList<>()).add(new Span(a, a));
                edits.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
            }
        }
        spans.forEach((k, list) -> {
            TaskCatalog.Task task = index.tasks().get(k);
            String title = task == null ? k : task.title();
            for (Block b : blocks(list)) {
                prompts.getOrDefault(k, List.of()).stream().filter(p -> in(ms(p.at()), b))
                        .forEach(p -> b.prompts.add(oneLine(p.text(), 80)));
                edits.getOrDefault(k, List.of()).stream().filter(e -> in(ms(e.at()), b))
                        .forEach(e -> b.files.add(e.path().replaceAll(".*/", "")));
                StringBuilder desc = new StringBuilder();
                desc.append(task == null || task.project() == null ? "" : task.project() + " · ")
                        .append(Math.round((b.to - b.from) / 60_000.0)).append("분\n");
                if (!b.prompts.isEmpty()) {
                    desc.append("\n요청:\n");
                    b.prompts.stream().limit(5).forEach(t -> desc.append("- ").append(t).append('\n'));
                    if (b.prompts.size() > 5) {
                        desc.append("- …외 ").append(b.prompts.size() - 5).append("건\n");
                    }
                }
                if (!b.files.isEmpty()) {
                    desc.append("\n수정 파일: ").append(String.join(", ", b.files.stream().limit(10).toList()))
                            .append(b.files.size() > 10 ? " 외 " + (b.files.size() - 10) + "개" : "").append('\n');
                }
                desc.append("\nVita에서 보기: ").append(APP).append("/sessions/").append(k);
                result.add(new TimedEvent("vita-blk-" + k + "-" + b.from + "@vita", b.from, b.to, title, desc.toString(), APP + "/sessions/" + k));
            }
        });
        for (Event e : deploy.events(from.toString(), to.toString())) {
            Long end = ms(e.finishedAt());
            if (end == null) {
                continue;
            }
            long start = end - (e.duration() == null ? 0 : Math.round(e.duration() * 1000));
            if (end - start < MIN_BLOCK_MS) {
                start = end - MIN_BLOCK_MS;
            }
            boolean ok = "success".equals(e.status());
            result.add(new TimedEvent("vita-dep-" + e.project().hashCode() + "-" + e.jobId() + "@vita", start, end,
                    e.label() + (ok ? " ✓" : " ✕"), e.shortName() + " " + (e.sha() == null ? "" : e.sha()) + (ok ? " 성공" : " 실패(" + e.status() + ")")
                            + (e.webUrl() == null ? "" : "\nGitLab: " + e.webUrl()), e.webUrl()));
        }
        return result;
    }

    /** 구간들을 시작순으로 이어 붙이고, 15분 넘게 비면 끊는다. 5분 미만은 뺀다 */
    static List<Block> blocks(List<Span> spans) {
        List<Span> sorted = new ArrayList<>(spans);
        sorted.sort(java.util.Comparator.comparingLong(Span::from));
        List<Block> out = new ArrayList<>();
        Block cur = null;
        for (Span s : sorted) {
            if (cur != null && s.from() - cur.to <= GAP_MS) {
                cur.to = Math.max(cur.to, s.to());
                continue;
            }
            if (cur != null && cur.to - cur.from >= MIN_BLOCK_MS) {
                out.add(cur);
            }
            cur = new Block();
            cur.from = s.from();
            cur.to = s.to();
        }
        if (cur != null && cur.to - cur.from >= MIN_BLOCK_MS) {
            out.add(cur);
        }
        return out;
    }

    private static boolean in(Long t, Block b) {
        return t != null && t >= b.from && t <= b.to;
    }

    private static Long ms(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String oneLine(String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private static void head(StringBuilder out, String name) {
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//Vita//Session calendar//KO");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:PUBLISH");
        line(out, "X-WR-CALNAME:" + name);
    }

    private static void timed(StringBuilder out, String uid, String stamp, long from, long to, String summary, String desc, String url) {
        line(out, "BEGIN:VEVENT");
        line(out, "UID:" + uid);
        line(out, "DTSTAMP:" + stamp);
        line(out, "DTSTART:" + UTC.format(Instant.ofEpochMilli(from)));
        line(out, "DTEND:" + UTC.format(Instant.ofEpochMilli(to)));
        line(out, "SUMMARY:" + esc(summary));
        line(out, "DESCRIPTION:" + esc(desc));
        if (url != null) {
            line(out, "URL:" + url);
        }
        line(out, "TRANSP:OPAQUE");
        line(out, "END:VEVENT");
    }

    private record DayEntry(LocalDate day, long sec, double cost, List<String> summaries, Set<String> sessionIds) {
    }

    private static final class Task {
        String key;
        String title;
        String project;
        final List<DayEntry> days = new ArrayList<>();
    }

    public String ics(LocalDate from, LocalDate to) {
        Map<String, Task> tasks = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayDetail dd = query.day(d);
            for (DayTask t : dd.tasks()) {
                Task task = tasks.computeIfAbsent(t.taskKey(), k -> new Task());
                task.key = t.taskKey();
                task.title = t.title();
                task.project = t.project();
                List<String> sums = new ArrayList<>();
                Set<String> ids = new LinkedHashSet<>();
                for (DaySession s : t.sessions()) {
                    ids.add(s.id());
                    if (s.summary() != null && !s.summary().isBlank()) {
                        String one = s.summary().strip().lines().findFirst().orElse("");
                        // 일정 설명은 짧게 — 날마다 한 줄(약 120자), 전문은 Vita 에서
                        sums.add(one.length() > 120 ? one.substring(0, 120).strip() + "…" : one);
                    }
                }
                task.days.add(new DayEntry(d, t.activeSec(), t.costUsd(), sums, ids));
            }
        }
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//Vita//Session calendar//KO");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:PUBLISH");
        line(out, "X-WR-CALNAME:Vita");
        String stamp = Instant.now().toString().replaceAll("[-:]", "").replaceAll("\\.\\d+", "");
        for (Task t : tasks.values()) {
            for (List<DayEntry> seg : segments(t.days)) {
                LocalDate start = seg.getFirst().day();
                LocalDate end = seg.getLast().day();
                event(out, "vita-" + t.key + "-" + start.format(D) + "@vita", stamp, start, end, t.title, describe(t, seg),
                        APP + "/sessions/" + t.key);
            }
        }
        Map<String, List<Event>> deploys = new LinkedHashMap<>();
        for (Event e : deploy.events(from.toString(), to.toString())) {
            deploys.computeIfAbsent(e.day() + "|" + e.label(), k -> new ArrayList<>()).add(e);
        }
        deploys.forEach((k, list) -> {
            Event first = list.getFirst();
            LocalDate day = LocalDate.parse(first.day());
            boolean ok = list.stream().allMatch(e -> "success".equals(e.status()));
            StringBuilder desc = new StringBuilder();
            desc.append(list.size()).append("회 · ").append(ok ? "모두 성공" : "실패 있음").append('\n');
            for (Event e : list) {
                desc.append(e.finishedAt() == null ? "" : time(e.finishedAt())).append(' ').append(e.shortName()).append(' ')
                        .append(e.sha() == null ? "" : e.sha()).append(' ').append("success".equals(e.status()) ? "✓" : "✕ " + e.status())
                        .append(e.duration() == null ? "" : " " + Math.round(e.duration()) + "초").append('\n');
            }
            Event last = list.getLast();
            if (last.webUrl() != null) {
                desc.append("GitLab: ").append(last.webUrl());
            }
            event(out, "vita-deploy-" + Integer.toHexString(first.source().hashCode()) + "-" + day.format(D) + "@vita", stamp, day, day,
                    first.label() + (ok ? "" : " ✕"), desc.toString().strip(), last.webUrl());
        });
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    /** 이어진 날짜끼리 — 하루라도 비면 끊는다(구글은 주말도 보이므로 주말을 건너 잇지 않는다) */
    static <T> List<List<T>> runs(List<T> days, java.util.function.Function<T, LocalDate> dayOf) {
        List<List<T>> out = new ArrayList<>();
        List<T> cur = new ArrayList<>();
        for (T d : days) {
            if (!cur.isEmpty() && !dayOf.apply(cur.getLast()).plusDays(1).equals(dayOf.apply(d))) {
                out.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(d);
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    private static List<List<DayEntry>> segments(List<DayEntry> days) {
        return runs(days, DayEntry::day);
    }

    private String describe(Task t, List<DayEntry> seg) {
        Set<String> ids = new LinkedHashSet<>();
        seg.forEach(d -> ids.addAll(d.sessionIds()));
        List<String> todos = todos(ids, seg.getFirst().day(), seg.getLast().day());
        long sec = seg.stream().mapToLong(DayEntry::sec).sum();
        double cost = seg.stream().mapToDouble(DayEntry::cost).sum();
        StringBuilder sb = new StringBuilder();
        sb.append(t.project == null ? "" : t.project).append(todos.isEmpty() ? "" : (t.project == null ? "" : " · ") + "할 일: " + String.join(", ", todos))
                .append('\n');
        sb.append("작업 ").append(hours(sec)).append(" · 사용량 $").append(String.format(Locale.ROOT, "%.1f", cost)).append(" (API 환산)\n\n");
        for (DayEntry d : seg) {
            sb.append(d.day().getMonthValue()).append('/').append(d.day().getDayOfMonth()).append(' ').append(hours(d.sec()));
            if (!d.summaries().isEmpty()) {
                sb.append(" — ").append(String.join(" / ", d.summaries()));
            }
            sb.append('\n');
        }
        sb.append('\n').append("Vita에서 보기: ").append(APP).append("/sessions/").append(t.key);
        return sb.toString();
    }

    /** 이 세션들이 그 기간에 붙은 할 일 제목 */
    private List<String> todos(Set<String> sessionIds, LocalDate from, LocalDate to) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", sessionIds.stream().map(x -> "?").toList());
        List<Object> params = new ArrayList<>(sessionIds);
        params.add(from.toString());
        params.add(to.toString());
        return jdbc.sql("SELECT DISTINCT wi.title FROM work_link wl JOIN work_item wi ON wi.id = wl.work_item_id WHERE wl.session_id IN ("
                + in + ") AND wl.day >= ? AND wl.day <= ? ORDER BY wi.title").params(params).query(String.class).list();
    }

    private static String hours(long sec) {
        if (sec < 3600) {
            return Math.max(1, Math.round(sec / 60.0)) + "m";
        }
        return String.format(Locale.ROOT, "%.1fh", sec / 3600.0);
    }

    private static String time(String iso) {
        return java.time.ZonedDateTime.ofInstant(Instant.parse(iso), java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static void event(StringBuilder out, String uid, String stamp, LocalDate start, LocalDate end, String summary, String desc, String url) {
        line(out, "BEGIN:VEVENT");
        line(out, "UID:" + uid);
        line(out, "DTSTAMP:" + stamp);
        line(out, "DTSTART;VALUE=DATE:" + start.format(D));
        line(out, "DTEND;VALUE=DATE:" + end.plusDays(1).format(D));
        line(out, "SUMMARY:" + esc(summary));
        line(out, "DESCRIPTION:" + esc(desc));
        if (url != null) {
            line(out, "URL:" + url);
        }
        line(out, "TRANSP:TRANSPARENT");
        line(out, "END:VEVENT");
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r", "").replace("\n", "\\n");
    }

    /** RFC 5545 — 한 줄 75바이트 넘으면 접는다(다음 줄은 공백 하나로 시작), 줄 끝은 CRLF */
    static void line(StringBuilder out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        int i = 0;
        boolean first = true;
        while (i < b.length) {
            int max = first ? 75 : 74;
            int end = Math.min(b.length, i + max);
            while (end < b.length && (b[end] & 0xC0) == 0x80) {
                end--; // UTF-8 글자 가운데서 자르지 않는다
            }
            if (!first) {
                out.append(' ');
            }
            out.append(new String(b, i, end - i, StandardCharsets.UTF_8)).append("\r\n");
            i = end;
            first = false;
        }
        if (b.length == 0) {
            out.append("\r\n");
        }
    }
}
