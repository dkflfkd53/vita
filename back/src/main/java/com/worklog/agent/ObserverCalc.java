package com.worklog.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.worklog.agent.AgentStore.EventRow;
import com.worklog.agent.AgentStore.ToolCallRow;
import com.worklog.agent.AgentStore.UsageSplitRow;
import com.worklog.agent.ObserverViews.Breakdown;
import com.worklog.agent.ObserverViews.CommandStat;
import com.worklog.agent.ObserverViews.Counts;
import com.worklog.agent.ObserverViews.ErrorSample;
import com.worklog.agent.ObserverViews.ModelCost;
import com.worklog.agent.ObserverViews.PromptSample;
import com.worklog.agent.ObserverViews.ReworkFile;
import com.worklog.agent.ObserverViews.SessionStat;
import com.worklog.agent.ObserverViews.Snapshot;
import com.worklog.agent.ObserverViews.ToolStat;
import com.worklog.config.WorklogProperties;
import com.worklog.config.WorklogProperties.ModelPrice;
import com.worklog.query.QueryStore;
import com.worklog.query.Rows.EditRow;
import com.worklog.query.Rows.PromptRow;
import com.worklog.query.Rows.SessionDayRow;
import com.worklog.query.Rows.SessionRow;
import com.worklog.query.TaskCatalog;
import com.worklog.usage.Pricing;

import lombok.RequiredArgsConstructor;

/**
 * 기간 데이터를 한 번 읽어(Data) 그 안의 아무 구간·범위(카테고리)로 지표를 계산한다.
 * 주별 추이 8주처럼 여러 구간을 볼 때 DB 를 구간마다 다시 읽지 않으려고 나눠 둔다.
 */
@Component
@RequiredArgsConstructor
public class ObserverCalc {

    /** 원래 즉시 끝나는 도구 — 2초 넘게 걸렸으면 넘친 만큼을 승인 대기로 본다 */
    private static final Set<String> INSTANT = Set.of("Read", "Write", "Edit", "NotebookEdit", "Grep", "Glob", "LS",
            "ToolSearch", "TaskCreate", "TaskUpdate", "TaskList", "TaskGet", "TodoWrite", "Skill",
            "apply_patch"); // Codex 파일 수정
    /** 셸 명령 도구 — Claude Code 의 Bash, Codex 의 exec_command */
    private static final Set<String> SHELL = Set.of("Bash", "exec_command");
    private static final long INSTANT_GRACE_MS = 2_000;
    /** 사용자에게 묻고 답을 기다리는 도구 — 걸린 시간 전체가 질문 응답 대기 */
    private static final Set<String> ASK = Set.of("AskUserQuestion", "ExitPlanMode", "EnterPlanMode", "request_user_input_async");
    /** 결과 없이 끝나지 않은 호출·백그라운드 등 이상치 상한 */
    private static final long TOOL_CAP_MS = Duration.ofMinutes(60).toMillis();
    private static final Pattern CORRECTION = Pattern.compile(
            "^\\s*(ㄴㄴ|ㄴ\\s|아니|아냐|엥|노노|그게 아니)|다시 해|잘못 |잘못됐|틀렸|그게 아니|그거 아니|안 ?되는데|안 ?돼|안 ?됨|왜 안|이상한데|이상해|안 ?나와|안 ?먹|되돌려|원래대로|클릭이 ?안");

    private final QueryStore query;
    private final TaskCatalog catalog;
    private final AgentStore store;
    private final Pricing pricing;
    private final WorklogProperties props;

    /** 읽어 둔 기간 데이터. sessions = 관리 대상 세션(project 는 카테고리 표시 이름) */
    public record Data(Map<String, SessionRow> sessions, Map<String, String> taskOf, List<SessionDayRow> days,
                       List<PromptRow> prompts, List<EditRow> edits, List<ToolCallRow> tools, List<EventRow> events,
                       List<UsageSplitRow> usage) {
    }

    public Data load(LocalDate from, LocalDate to) {
        String f = from.toString();
        String t = to.toString();
        Map<String, SessionRow> sessions = query.sessions().stream().filter(s -> !s.hidden())
                .collect(Collectors.toMap(SessionRow::id, s -> s, (a, b) -> a));
        Map<String, String> taskOf = catalog.build().taskOfSession();
        Predicate<String> managed = sessions::containsKey;
        return new Data(sessions, taskOf,
                query.sessionDays(f, t).stream().filter(d -> managed.test(d.sessionId())).toList(),
                query.prompts(sessions.keySet(), f, t),
                query.edits(sessions.keySet(), f, t),
                store.toolCalls(f, t).stream().filter(c -> managed.test(c.sessionId())).toList(),
                store.events(f, t).stream().filter(e -> managed.test(e.sessionId())).toList(),
                store.usage(f, t).stream().filter(u -> managed.test(u.sessionId())).toList());
    }

    /** 구간·범위로 거른 데이터 */
    public Data slice(Data d, LocalDate from, LocalDate to, String scope) {
        String f = from.toString();
        String t = to.toString();
        Predicate<String> inDay = day -> day.compareTo(f) >= 0 && day.compareTo(t) <= 0;
        Predicate<String> inScope = sid -> {
            SessionRow s = d.sessions().get(sid);
            return s != null && (scope == null || scope.equals(s.project()));
        };
        return new Data(d.sessions(), d.taskOf(),
                d.days().stream().filter(x -> inDay.test(x.day()) && inScope.test(x.sessionId())).toList(),
                d.prompts().stream().filter(x -> inDay.test(x.day()) && inScope.test(x.sessionId())).toList(),
                d.edits().stream().filter(x -> inDay.test(x.day()) && inScope.test(x.sessionId())).toList(),
                d.tools().stream().filter(x -> inDay.test(x.day()) && inScope.test(x.sessionId())).toList(),
                d.events().stream().filter(x -> inDay.test(x.day()) && inScope.test(x.sessionId())).toList(),
                d.usage().stream().filter(x -> inDay.test(x.day()) && inScope.test(x.sessionId())).toList());
    }

    public Snapshot snapshot(Data d) {
        long activeSec = d.days().stream().mapToLong(SessionDayRow::activeSec).sum();
        List<EventRow> turns = d.events().stream().filter(e -> "turn".equals(e.kind())).toList();
        long turnMs = turns.stream().mapToLong(e -> e.valueMs() == null ? 0 : e.valueMs()).sum();

        long toolMs = 0;
        long approvalMs = 0;
        long askMs = 0;
        int errors = 0;
        int rejected = 0;
        int finished = 0;
        for (ToolCallRow c : d.tools()) {
            if (c.status() != null) {
                finished++;
                if ("error".equals(c.status())) {
                    errors++;
                } else if ("rejected".equals(c.status())) {
                    rejected++;
                }
            }
            if (c.sidechain()) {
                continue; // 서브에이전트 도구는 본체 Agent 호출 시간 안에 들어 있다
            }
            long ms = durationMs(c);
            if (ASK.contains(c.tool())) {
                askMs += ms;
            } else if (INSTANT.contains(c.tool())) {
                approvalMs += Math.max(0, ms - INSTANT_GRACE_MS);
                toolMs += Math.min(ms, INSTANT_GRACE_MS);
            } else {
                toolMs += ms;
            }
        }
        long nextWaitMs = nextPromptWaitMs(d);
        long modelMs = Math.max(0, turnMs - toolMs - approvalMs - askMs);

        int prompts = d.prompts().size();
        int corrections = (int) d.prompts().stream().filter(p -> isCorrection(p.text())).count();
        int interrupts = (int) d.events().stream().filter(e -> "interrupt".equals(e.kind())).count();
        int compactions = (int) d.events().stream().filter(e -> "compact".equals(e.kind())).count();

        double cost = 0;
        double writeCost = 0;
        double subCost = 0;
        for (UsageSplitRow u : d.usage()) {
            ModelPrice p = pricing.price(u.model()).orElse(null);
            if (p == null) {
                continue;
            }
            double c = pricing.cost(p, u.input(), u.output(), u.cacheRead(), u.cacheWrite5m(), u.cacheWrite1h());
            cost += c;
            writeCost += pricing.cost(p, 0, 0, 0, u.cacheWrite5m(), u.cacheWrite1h());
            if (u.sidechain()) {
                subCost += c;
            }
        }
        long editedFiles = d.edits().stream().map(e -> e.sessionId() + "|" + e.path()).distinct().count();
        Set<String> sessions = new HashSet<>();
        d.days().stream().filter(x -> x.activeSec() > 0).forEach(x -> sessions.add(x.sessionId()));

        double hours = activeSec / 3600.0;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put(Metric.ERROR_RATE.key, finished - rejected > 0 ? 100.0 * errors / (finished - rejected) : null);
        m.put(Metric.CORRECTION_RATE.key, prompts > 0 ? 100.0 * corrections / prompts : null);
        m.put(Metric.INTERRUPT_RATE.key, prompts > 0 ? 100.0 * interrupts / prompts : null);
        m.put(Metric.WAIT_SHARE.key, activeSec > 0 && !turns.isEmpty()
                ? Math.min(100, 100.0 * (approvalMs + askMs + nextWaitMs) / 1000 / activeSec) : null);
        m.put(Metric.TOOL_SHARE.key, turnMs > 0 ? Math.min(100, 100.0 * toolMs / turnMs) : null);
        m.put(Metric.REWORK.key, editedFiles > 0 ? (double) d.edits().size() / editedFiles : null);
        m.put(Metric.COMPACT_RATE.key, hours >= 1 && !turns.isEmpty() ? compactions / hours * 10 : null);
        m.put(Metric.COST_PER_HOUR.key, hours > 0 ? cost / hours : null);
        m.put(Metric.CACHE_WRITE_SHARE.key, cost > 0 ? 100.0 * writeCost / cost : null);
        m.put(Metric.SUBAGENT_SHARE.key, cost > 0 ? 100.0 * subCost / cost : null);
        m.put(Metric.AVG_TURN.key, turns.isEmpty() ? null : turnMs / 60_000.0 / turns.size());

        return new Snapshot(
                new Breakdown(activeSec, turnMs / 1000, modelMs / 1000, toolMs / 1000, approvalMs / 1000, askMs / 1000,
                        nextWaitMs / 1000),
                new Counts(sessions.size(), prompts, turns.size(), d.tools().size(), errors, rejected, interrupts,
                        corrections, compactions, d.edits().size(), cost),
                m);
    }

    /** 일한 평일 수(작업 기록이 있는 날) — 효과 측정이 의미 있을 만큼 쌓였는지 본다 */
    public int workedDays(Data d) {
        return (int) d.days().stream().filter(x -> x.activeSec() > 0).map(SessionDayRow::day).distinct().count();
    }

    /** Claude 가 턴을 끝낸 뒤 내가 다음 요청을 보낼 때까지. 15분(작업 공백 기준) 넘는 건 쉬는 시간이라 뺀다 */
    private long nextPromptWaitMs(Data d) {
        long gapMs = props.idleGap().toMillis();
        Map<String, List<Instant>> turnEnds = new HashMap<>();
        for (EventRow e : d.events()) {
            if ("turn".equals(e.kind())) {
                turnEnds.computeIfAbsent(e.sessionId(), k -> new ArrayList<>()).add(Instant.parse(e.at()));
            }
        }
        turnEnds.values().forEach(l -> l.sort(null));
        long total = 0;
        Map<String, List<PromptRow>> bySession = d.prompts().stream().collect(Collectors.groupingBy(PromptRow::sessionId));
        for (Map.Entry<String, List<PromptRow>> e : bySession.entrySet()) {
            List<Instant> ends = turnEnds.getOrDefault(e.getKey(), List.of());
            if (ends.isEmpty()) {
                continue;
            }
            Instant prev = Instant.EPOCH;
            for (PromptRow p : e.getValue().stream().sorted(Comparator.comparing(PromptRow::at)).toList()) {
                Instant at = Instant.parse(p.at());
                Instant lastEnd = null;
                for (Instant end : ends) {
                    if (end.isBefore(at) && end.isAfter(prev)) {
                        lastEnd = end;
                    }
                }
                if (lastEnd != null) {
                    long w = Duration.between(lastEnd, at).toMillis();
                    if (w <= gapMs) {
                        total += w;
                    }
                }
                prev = at;
            }
        }
        return total;
    }

    static long durationMs(ToolCallRow c) {
        if (c.endedAt() == null) {
            return 0;
        }
        long ms = Duration.between(Instant.parse(c.startedAt()), Instant.parse(c.endedAt())).toMillis();
        return Math.max(0, Math.min(ms, TOOL_CAP_MS));
    }

    static boolean isCorrection(String text) {
        return text != null && CORRECTION.matcher(text.length() > 400 ? text.substring(0, 400) : text).find();
    }

    // ───────── 병목 목록 ─────────

    public List<ToolStat> tools(Data d) {
        Map<String, List<ToolCallRow>> by = d.tools().stream().collect(Collectors.groupingBy(ToolCallRow::tool));
        return by.entrySet().stream().map(e -> {
            List<ToolCallRow> l = e.getValue();
            long total = l.stream().mapToLong(ObserverCalc::durationMs).sum();
            long max = l.stream().mapToLong(ObserverCalc::durationMs).max().orElse(0);
            return new ToolStat(e.getKey(), l.size(), (int) l.stream().filter(c -> "error".equals(c.status())).count(),
                    (int) l.stream().filter(c -> "rejected".equals(c.status())).count(), total / 1000,
                    l.isEmpty() ? 0 : total / 1000.0 / l.size(), max / 1000);
        }).sorted(Comparator.comparingLong(ToolStat::totalSec).reversed()).toList();
    }

    /** 셸 명령(Bash·Codex exec_command)을 "./gradlew compileJava"·"npx tsc" 처럼 묶어 오래 걸린 순 */
    public List<CommandStat> slowCommands(Data d, int limit) {
        Map<String, List<ToolCallRow>> by = d.tools().stream()
                .filter(c -> SHELL.contains(c.tool()) && c.target() != null)
                .collect(Collectors.groupingBy(c -> signature(c.target())));
        return by.entrySet().stream().map(e -> {
            List<ToolCallRow> l = e.getValue();
            ToolCallRow slowest = l.stream().max(Comparator.comparingLong(ObserverCalc::durationMs)).orElseThrow();
            return new CommandStat(e.getKey(), l.size(), (int) l.stream().filter(c -> "error".equals(c.status())).count(),
                    l.stream().mapToLong(ObserverCalc::durationMs).sum() / 1000, durationMs(slowest) / 1000, slowest.target());
        }).sorted(Comparator.comparingLong(CommandStat::totalSec).reversed()).limit(limit).toList();
    }

    /** 무거운 일을 하는 명령 — 여러 줄 명령에서 이 중 처음 나오는 것을 묶음 이름으로 */
    private static final Set<String> HEAVY = Set.of("./gradlew", "gradle", "mvn", "./mvnw", "npm", "npx", "pnpm", "yarn", "node",
            "java", "git", "gh", "curl", "docker", "ssh", "scp", "psql", "sqlite3", "redis-cli", "python3", "python", "sleep",
            "launchctl", "openssl", "kubectl", "rsync", "make");
    private static final Set<String> SKIP = Set.of("cd", "export", "set", "source", "echo", "printf", "fi", "done", "EOF", "}", ")");
    /** 줄 앞에 붙는 셸 문법 — 건너뛰고 뒤 명령을 본다 */
    private static final Set<String> PREFIX = Set.of("do", "then", "else", "{", "(", "time", "nohup", "!");

    static String signature(String command) {
        String[] segments = command.replace(" …", "").split("\\s*(?:&&|\\|\\||;|\\||⏎)\\s*");
        String fallback = null;
        for (String seg : segments) {
            String[] w = seg.strip().split("\\s+");
            int k = 0;
            while (k < w.length && (w[k].matches("[A-Za-z_][A-Za-z0-9_]*=.*") || PREFIX.contains(w[k]))) {
                k++; // FOO=bar 명령, do 명령 → 명령
            }
            if (k >= w.length || w[k].isEmpty() || SKIP.contains(w[k]) || w[k].startsWith("#") || w[k].startsWith("'")) {
                continue;
            }
            String head = w[k].startsWith("/") || w[k].startsWith("~") ? w[k].substring(w[k].lastIndexOf('/') + 1) : w[k];
            String sig = head;
            if (k + 1 < w.length && w[k + 1].matches("[A-Za-z][\\w:.-]*") && !head.equals("sleep")) {
                sig = head + " " + w[k + 1];
            }
            if (HEAVY.contains(head)) {
                return sig;
            }
            if (fallback == null) {
                fallback = "for".equals(head) || "while".equals(head) ? head + " 반복" : sig;
            }
        }
        return fallback == null ? "(cd·export 만)" : fallback;
    }

    public List<ErrorSample> errors(Data d, int limit) {
        Map<String, List<ToolCallRow>> by = d.tools().stream().filter(c -> "error".equals(c.status()))
                .collect(Collectors.groupingBy(c -> c.tool() + "|" + (c.target() == null ? "" : SHELL.contains(c.tool())
                        ? signature(c.target()) : c.target()) + "|" + firstLine(c.errorText())));
        return by.values().stream().map(l -> {
            ToolCallRow last = l.stream().max(Comparator.comparing(ToolCallRow::startedAt)).orElseThrow();
            SessionRow s = d.sessions().get(last.sessionId());
            return new ErrorSample(last.tool(), last.target(), firstLine(last.errorText()), l.size(), last.startedAt(),
                    s == null ? null : s.displayTitle(), d.taskOf().get(last.sessionId()));
        }).sorted(Comparator.comparingInt(ErrorSample::count).reversed().thenComparing(ErrorSample::lastAt, Comparator.reverseOrder()))
                .limit(limit).toList();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        // "Exit code 1" 처럼 의미 없는 첫 줄이면 다음 줄까지
        String[] lines = t.split("\n");
        String first = lines[0].strip();
        if (first.startsWith("Exit code") && lines.length > 1) {
            first = first + " · " + lines[1].strip();
        }
        return first.length() > 160 ? first.substring(0, 160) + "…" : first;
    }

    public List<PromptSample> corrections(Data d, int limit) {
        return d.prompts().stream().filter(p -> isCorrection(p.text()))
                .sorted(Comparator.comparing(PromptRow::at).reversed()).limit(limit)
                .map(p -> {
                    SessionRow s = d.sessions().get(p.sessionId());
                    String t = p.text().strip().replace('\n', ' ');
                    return new PromptSample(t.length() > 160 ? t.substring(0, 160) + "…" : t, p.at(),
                            s == null ? null : s.displayTitle(), d.taskOf().get(p.sessionId()));
                }).toList();
    }

    public List<ReworkFile> rework(Data d, int minEdits, int limit) {
        Map<String, List<EditRow>> by = d.edits().stream().collect(Collectors.groupingBy(e -> e.sessionId() + "|" + e.path()));
        return by.values().stream().filter(l -> l.size() >= minEdits).map(l -> {
            EditRow e = l.get(0);
            SessionRow s = d.sessions().get(e.sessionId());
            return new ReworkFile(shortPath(e.path()), l.size(), s == null ? null : s.displayTitle(), d.taskOf().get(e.sessionId()));
        }).sorted(Comparator.comparingInt(ReworkFile::edits).reversed()).limit(limit).toList();
    }

    private static String shortPath(String path) {
        String home = System.getProperty("user.home");
        String p = path.startsWith(home) ? "~" + path.substring(home.length()) : path;
        return p.replaceFirst("^~/\\.claude/projects/[^/]+/memory/", "메모리/")
                .replaceFirst("^/private/tmp/claude-\\d+/[^/]+/[^/]+/scratchpad/", "scratchpad/");
    }

    public List<SessionStat> sessions(Data d, int limit) {
        Map<String, Long> active = new HashMap<>();
        d.days().forEach(x -> active.merge(x.sessionId(), x.activeSec(), Long::sum));
        Map<String, Double> cost = new HashMap<>();
        for (UsageSplitRow u : d.usage()) {
            pricing.price(u.model()).ifPresent(p -> cost.merge(u.sessionId(),
                    pricing.cost(p, u.input(), u.output(), u.cacheRead(), u.cacheWrite5m(), u.cacheWrite1h()), Double::sum));
        }
        Map<String, List<ToolCallRow>> tools = d.tools().stream().collect(Collectors.groupingBy(ToolCallRow::sessionId));
        Map<String, List<EventRow>> events = d.events().stream().collect(Collectors.groupingBy(EventRow::sessionId));
        Map<String, Long> corrections = d.prompts().stream().filter(p -> isCorrection(p.text()))
                .collect(Collectors.groupingBy(PromptRow::sessionId, Collectors.counting()));
        Set<String> ids = new HashSet<>(active.keySet());
        ids.addAll(cost.keySet());
        return ids.stream().map(id -> {
            SessionRow s = d.sessions().get(id);
            List<ToolCallRow> t = tools.getOrDefault(id, List.of());
            List<EventRow> ev = events.getOrDefault(id, List.of());
            return new SessionStat(id, d.taskOf().get(id), s == null ? id : s.displayTitle(), s == null ? null : s.project(),
                    active.getOrDefault(id, 0L), cost.getOrDefault(id, 0.0), t.size(),
                    (int) t.stream().filter(c -> "error".equals(c.status())).count(),
                    corrections.getOrDefault(id, 0L).intValue(),
                    (int) ev.stream().filter(e -> "compact".equals(e.kind())).count(),
                    ev.stream().filter(e -> "turn".equals(e.kind()) && e.valueMs() != null).mapToLong(EventRow::valueMs).max().orElse(0) / 1000);
        }).sorted(Comparator.comparingDouble(SessionStat::costUsd).reversed()).limit(limit).toList();
    }

    public List<ModelCost> models(Data d) {
        Map<String, Double> by = new HashMap<>();
        for (UsageSplitRow u : d.usage()) {
            pricing.price(u.model()).ifPresent(p -> by.merge(u.model(),
                    pricing.cost(p, u.input(), u.output(), u.cacheRead(), u.cacheWrite5m(), u.cacheWrite1h()), Double::sum));
        }
        double total = by.values().stream().mapToDouble(Double::doubleValue).sum();
        return by.entrySet().stream().map(e -> new ModelCost(e.getKey(), e.getValue(), total > 0 ? 100 * e.getValue() / total : 0))
                .sorted(Comparator.comparingDouble(ModelCost::costUsd).reversed()).toList();
    }
}
