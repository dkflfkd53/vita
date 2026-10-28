package com.worklog.query;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.worklog.collect.CollectScheduler;
import com.worklog.config.ProjectScope;
import com.worklog.config.RoutineSessions;
import com.worklog.live.LiveSessionService;
import com.worklog.usage.UsageService;
import com.worklog.usage.UsageTotal;
import com.worklog.live.LiveSessionService.LiveSession;
import com.worklog.query.Rows.CommitRow;
import com.worklog.query.Rows.DaySummaryRow;
import com.worklog.query.Rows.EditRow;
import com.worklog.query.Rows.PromptRow;
import com.worklog.query.Rows.SessionDayRow;
import com.worklog.query.Rows.SessionRow;
import com.worklog.query.TaskCatalog.Index;
import com.worklog.query.TaskCatalog.Task;
import com.worklog.query.Views.UsageBreakdown;
import com.worklog.query.Views.CalendarDay;
import com.worklog.query.Views.CalendarItem;
import com.worklog.query.Views.CommitView;
import com.worklog.query.Views.DayDetail;
import com.worklog.query.Views.DaySession;
import com.worklog.query.Views.DayTask;
import com.worklog.query.Views.EditView;
import com.worklog.query.Views.HeatDay;
import com.worklog.query.Views.LiveView;
import com.worklog.query.Views.Overview;
import com.worklog.query.Views.PeriodStats;
import com.worklog.query.Views.ProjectStat;
import com.worklog.query.Views.PromptView;
import com.worklog.query.Views.SessionDayRef;
import com.worklog.query.Views.SessionInfo;
import com.worklog.query.Views.TaskDay;
import com.worklog.query.Views.TaskDetail;
import com.worklog.query.Views.TaskStat;
import com.worklog.query.Views.TaskSummary;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WorkQueryService {

    private static final Duration RECENT = Duration.ofDays(3);
    private static final int RECENT_PROMPTS = 200;

    private final QueryStore store;
    private final TaskCatalog catalog;
    private final LiveSessionService liveSessions;
    private final CollectScheduler collector;
    private final UsageService usage;
    private final ProjectScope scope;
    private final RoutineSessions routines;

    public Overview overview() {
        Index index = catalog.build();
        List<LiveSession> live = liveSessions.list();
        List<SessionDayRow> days = store.allSessionDays();
        UsageService.Index u = usage.all();

        List<TaskSummary> tasks = index.tasks().values().stream()
                .map(t -> summarize(t, days, live, u))
                // 열기만 하고 아무것도 안 한 세션은 목록에서 뺀다
                .filter(t -> "live".equals(t.status()) || t.activeDays() > 0)
                .sorted(Comparator.comparing((TaskSummary t) -> !"live".equals(t.status()))
                        .thenComparing(TaskSummary::lastAt, Comparator.reverseOrder()))
                .toList();

        // 같은 작업 묶음(이어진 세션 등)을 연 프로세스가 여럿이면 한 줄로 — 가장 최근에 움직인 것을 보이고 개수만 단다
        Map<String, List<LiveSession>> liveByTask = new LinkedHashMap<>();
        live.stream()
                .filter(l -> index.taskOf(l.sessionId()) != null || scope.labelForCwd(l.cwd()).isPresent())
                .forEach(l -> {
                    Task task = index.taskOf(l.sessionId());
                    liveByTask.computeIfAbsent(task != null ? task.key() : l.sessionId(), k -> new ArrayList<>()).add(l);
                });
        List<LiveView> liveViews = liveByTask.values().stream()
                .map(group -> {
                    LiveSession l = group.stream().max(Comparator.comparing(LiveSession::statusUpdatedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder()))).orElseThrow();
                    Task task = index.taskOf(l.sessionId());
                    String title = task != null ? task.title() : l.name();
                    return new LiveView(l.pid(), l.sessionId(), title, task == null ? null : task.key(),
                            task != null ? task.project() : scope.labelForCwd(l.cwd()).orElse(null), l.kind(), l.status(),
                            str(l.startedAt()), str(l.statusUpdatedAt()), group.size());
                })
                .sorted(Comparator.comparing(LiveView::statusUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return new Overview(liveViews, tasks, str(collector.lastCollectedAt()));
    }

    private TaskSummary summarize(Task task, List<SessionDayRow> allDays, List<LiveSession> live, UsageService.Index u) {
        Set<String> ids = ids(task);
        List<SessionDayRow> days = allDays.stream().filter(d -> ids.contains(d.sessionId())).toList();
        long active = days.stream().mapToLong(SessionDayRow::activeSec).sum();
        Set<String> activeDays = days.stream().filter(WorkQueryService::meaningful).map(SessionDayRow::day)
                .collect(Collectors.toSet());
        String lastDay = days.stream().map(SessionDayRow::day).max(String::compareTo).orElse(null);
        String lastSummary = days.stream().filter(d -> d.summary() != null)
                .max(Comparator.comparing(SessionDayRow::day)).map(SessionDayRow::summary).orElse(null);
        String lastAt = task.sessions().stream().map(SessionRow::lastAt).max(String::compareTo).orElse(null);
        String firstAt = task.sessions().stream().map(SessionRow::firstAt).min(String::compareTo).orElse(null);

        List<LiveSession> mine = live.stream().filter(l -> ids.contains(l.sessionId())).toList();
        String status = !mine.isEmpty() ? "live"
                : lastAt != null && Instant.parse(lastAt).isAfter(Instant.now().minus(RECENT)) ? "recent" : "idle";
        boolean busy = mine.stream().anyMatch(l -> "busy".equals(l.status()));
        return new TaskSummary(task.key(), task.title(), task.projects(), task.sessions().size(), firstAt, lastAt,
                lastDay, active, activeDays.size(),
                days.stream().mapToInt(SessionDayRow::prompts).sum(),
                days.stream().mapToInt(SessionDayRow::edits).sum(),
                status, busy, lastSummary, u.total(ids::contains).costUsd());
    }

    public List<CalendarDay> calendar(YearMonth month) {
        return calendar(month.atDay(1), month.atEndOfMonth());
    }

    /** 주 보기·월 격자처럼 달을 넘는 기간도 한 번에 */
    public List<CalendarDay> calendar(LocalDate fromDate, LocalDate toDate) {
        String from = fromDate.toString();
        String to = toDate.toString();
        Index index = catalog.build();
        Map<String, List<SessionDayRow>> byDay = visible(store.sessionDays(from, to), index).stream()
                .collect(Collectors.groupingBy(SessionDayRow::day, TreeMap::new, Collectors.toList()));
        Map<String, Long> commits = store.commits(from, to).stream()
                .collect(Collectors.groupingBy(CommitRow::day, Collectors.counting()));
        Map<String, String> summaries = store.daySummaries(from, to).stream()
                .collect(Collectors.toMap(DaySummaryRow::day, DaySummaryRow::overview));
        UsageService.Index u = usage.index(fromDate, toDate);
        Map<String, UsageTotal> dayCost = u.byDay(index.taskOfSession()::containsKey);

        Set<String> allDays = new HashSet<>(byDay.keySet());
        allDays.addAll(commits.keySet());
        return allDays.stream().sorted().map(day -> {
            List<SessionDayRow> rows = byDay.getOrDefault(day, List.of());
            Map<String, Long> perTask = new LinkedHashMap<>();
            Map<String, Double> perTaskCost = new HashMap<>();
            rows.forEach(r -> {
                String key = index.taskOfSession().get(r.sessionId());
                perTask.merge(key, r.activeSec(), Long::sum);
                perTaskCost.merge(key, u.cost(r.sessionId(), day), Double::sum);
            });
            List<CalendarItem> items = perTask.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> {
                        Task t = index.tasks().get(e.getKey());
                        return new CalendarItem(t.key(), t.title(), t.project(), e.getValue(), perTaskCost.get(e.getKey()),
                                routines.colorOf(t.title()));
                    }).toList();
            return new CalendarDay(day, rows.stream().mapToLong(SessionDayRow::activeSec).sum(),
                    commits.getOrDefault(day, 0L).intValue(), summaries.containsKey(day), summaries.get(day), items,
                    dayCost.getOrDefault(day, UsageTotal.ZERO).costUsd());
        }).toList();
    }

    public List<HeatDay> heatmap(LocalDate from, LocalDate to) {
        Index index = catalog.build();
        Map<String, Long> active = visible(store.sessionDays(from.toString(), to.toString()), index).stream()
                .collect(Collectors.groupingBy(SessionDayRow::day, Collectors.summingLong(SessionDayRow::activeSec)));
        Map<String, Long> commits = store.commits(from.toString(), to.toString()).stream()
                .collect(Collectors.groupingBy(CommitRow::day, Collectors.counting()));
        Map<String, UsageTotal> cost = usage.index(from, to).byDay(index.taskOfSession()::containsKey);
        List<HeatDay> result = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            String key = d.toString();
            result.add(new HeatDay(key, active.getOrDefault(key, 0L), commits.getOrDefault(key, 0L).intValue(),
                    cost.getOrDefault(key, UsageTotal.ZERO).costUsd()));
        }
        return result;
    }

    public DayDetail day(LocalDate date) {
        String day = date.toString();
        Index index = catalog.build();
        List<SessionDayRow> rows = visible(store.sessionDays(day, day), index);
        Set<String> ids = rows.stream().map(SessionDayRow::sessionId).collect(Collectors.toSet());
        Map<String, List<PromptRow>> prompts = store.prompts(ids, day, day).stream()
                .collect(Collectors.groupingBy(PromptRow::sessionId));
        Map<String, List<EditRow>> edits = store.edits(ids, day, day).stream()
                .collect(Collectors.groupingBy(EditRow::sessionId));
        Map<String, SessionRow> sessions = new HashMap<>();
        index.tasks().values().forEach(t -> t.sessions().forEach(s -> sessions.put(s.id(), s)));
        UsageService.Index u = usage.index(date, date);

        Map<String, List<DaySession>> byTask = new LinkedHashMap<>();
        rows.stream().sorted(Comparator.comparing(SessionDayRow::firstAt, Comparator.nullsLast(String::compareTo)))
                .forEach(r -> {
                    SessionRow s = sessions.get(r.sessionId());
                    byTask.computeIfAbsent(index.taskOfSession().get(r.sessionId()), k -> new ArrayList<>())
                            .add(new DaySession(s.id(), s.displayTitle(), r.summary(), r.activeSec(), r.firstAt(),
                                    r.lastAt(),
                                    prompts.getOrDefault(s.id(), List.of()).stream()
                                            .map(p -> new PromptView(p.at(), p.text())).toList(),
                                    dedupeEdits(edits.getOrDefault(s.id(), List.of())), u.cost(s.id(), day)));
                });
        List<DayTask> tasks = byTask.entrySet().stream().map(e -> {
            Task t = index.tasks().get(e.getKey());
            List<DaySession> ss = e.getValue();
            return new DayTask(t.key(), t.title(), t.project(), ss.stream().mapToLong(DaySession::activeSec).sum(),
                    ss.stream().map(DaySession::firstAt).filter(Objects::nonNull).min(String::compareTo).orElse(null),
                    ss.stream().map(DaySession::lastAt).filter(Objects::nonNull).max(String::compareTo).orElse(null),
                    ss, ss.stream().mapToDouble(DaySession::costUsd).sum(), routines.colorOf(t.title()));
        }).sorted(Comparator.comparingLong(DayTask::activeSec).reversed()).toList();

        DaySummaryRow summary = store.daySummaries(day, day).stream().findFirst().orElse(null);
        List<CommitView> commits = store.commits(day, day).stream()
                .map(c -> new CommitView(c.repo(), c.hash(), c.at(), c.subject())).toList();
        return new DayDetail(day, summary == null ? null : summary.overview(),
                summary == null ? null : summary.generatedAt(),
                rows.stream().mapToLong(SessionDayRow::activeSec).sum(), tasks, commits,
                u.total(index.taskOfSession()::containsKey).costUsd());
    }

    public TaskDetail task(String key) {
        Index index = catalog.build();
        Task task = index.tasks().get(key);
        if (task == null) {
            return null;
        }
        Set<String> ids = ids(task);
        List<SessionDayRow> days = store.allSessionDays().stream().filter(d -> ids.contains(d.sessionId())).toList();
        UsageService.Index u = usage.all();
        TaskSummary summary = summarize(task, days, liveSessions.list(), u);

        List<TaskDay> timeline = days.stream()
                .collect(Collectors.groupingBy(SessionDayRow::day, TreeMap::new, Collectors.toList()))
                .descendingMap().entrySet().stream()
                .map(e -> new TaskDay(e.getKey(),
                        e.getValue().stream().mapToLong(SessionDayRow::activeSec).sum(),
                        e.getValue().stream().mapToInt(SessionDayRow::prompts).sum(),
                        e.getValue().stream().mapToInt(SessionDayRow::edits).sum(),
                        e.getValue().stream().map(SessionDayRow::summary).filter(Objects::nonNull).toList(),
                        e.getValue().stream().mapToDouble(r -> u.cost(r.sessionId(), r.day())).sum()))
                .toList();

        List<PromptRow> prompts = store.prompts(ids, "0000-00-00", "9999-99-99");
        List<PromptView> recent = prompts.subList(Math.max(0, prompts.size() - RECENT_PROMPTS), prompts.size())
                .stream().map(p -> new PromptView(p.at(), p.text())).toList().reversed();

        List<SessionInfo> sessions = task.sessions().stream()
                .sorted(Comparator.comparing(SessionRow::lastAt).reversed())
                .map(WorkQueryService::info).toList();
        return new TaskDetail(summary, sessions, timeline, recent);
    }

    public List<SessionInfo> hiddenSessions() {
        return store.sessions().stream().filter(SessionRow::hidden)
                .sorted(Comparator.comparing(SessionRow::lastAt).reversed())
                .map(WorkQueryService::info).toList();
    }

    public PeriodStats stats(LocalDate from, LocalDate to) {
        Index index = catalog.build();
        List<SessionDayRow> rows = visible(store.sessionDays(from.toString(), to.toString()), index);
        List<CommitRow> commits = store.commits(from.toString(), to.toString());

        UsageService.Index u = usage.index(from, to);
        Map<String, long[]> perProject = new TreeMap<>();
        Map<String, Double> projectCost = new HashMap<>();
        Map<String, Long> taskActive = new HashMap<>();
        Map<String, Double> taskCost = new HashMap<>();
        Map<String, Set<String>> taskDays = new HashMap<>();
        for (SessionDayRow r : rows) {
            Task t = index.taskOf(r.sessionId());
            String project = Objects.requireNonNullElse(t.project(), "기타");
            double cost = u.cost(r.sessionId(), r.day());
            perProject.computeIfAbsent(project, k -> new long[2])[0] += r.activeSec();
            projectCost.merge(project, cost, Double::sum);
            taskActive.merge(t.key(), r.activeSec(), Long::sum);
            taskCost.merge(t.key(), cost, Double::sum);
            if (meaningful(r)) {
                taskDays.computeIfAbsent(t.key(), k -> new HashSet<>()).add(r.day());
            }
        }
        commits.forEach(c -> perProject.computeIfAbsent(c.repo(), k -> new long[2])[1]++);

        List<ProjectStat> projects = perProject.entrySet().stream()
                .map(e -> new ProjectStat(e.getKey(), e.getValue()[0], (int) e.getValue()[1],
                        projectCost.getOrDefault(e.getKey(), 0.0)))
                .sorted(Comparator.comparingLong(ProjectStat::activeSec).reversed()
                        .thenComparing(ProjectStat::commits, Comparator.reverseOrder()))
                .toList();
        List<TaskStat> tasks = taskActive.entrySet().stream()
                .filter(e -> e.getValue() > 0 || taskDays.containsKey(e.getKey()))
                .map(e -> {
                    Task t = index.tasks().get(e.getKey());
                    return new TaskStat(t.key(), t.title(), t.project(), e.getValue(),
                            taskDays.getOrDefault(e.getKey(), Set.of()).size(), taskCost.getOrDefault(e.getKey(), 0.0));
                })
                .sorted(Comparator.comparingLong(TaskStat::activeSec).reversed()).toList();
        Set<String> workedDays = rows.stream().filter(WorkQueryService::meaningful).map(SessionDayRow::day)
                .collect(Collectors.toSet());
        return new PeriodStats(from.toString(), to.toString(), rows.stream().mapToLong(SessionDayRow::activeSec).sum(),
                workedDays.size(), tasks.size(), commits.size(), rows.stream().mapToInt(SessionDayRow::prompts).sum(),
                projects, tasks, heatmap(from, to), u.total(index.taskOfSession()::containsKey).costUsd(),
                u.byModel(index.taskOfSession()::containsKey));
    }

    /** 기간 사용량(관리 대상·숨기지 않은 세션). 비용 숫자를 누르면 보이는 토큰 내역 */
    public UsageBreakdown usage(LocalDate from, LocalDate to) {
        Index index = catalog.build();
        UsageService.Index u = usage.index(from, to);
        return new UsageBreakdown(from.toString(), to.toString(), u.total(index.taskOfSession()::containsKey),
                u.byModel(index.taskOfSession()::containsKey));
    }

    /** from~to 의 보이는 세션×날짜. 업무 보드·분류가 쓴다 */
    public List<SessionDayRef> sessionDayRefs(LocalDate from, LocalDate to) {
        Index index = catalog.build();
        Map<String, SessionRow> sessions = new HashMap<>();
        index.tasks().values().forEach(t -> t.sessions().forEach(s -> sessions.put(s.id(), s)));
        UsageService.Index u = usage.index(from, to);
        return visible(store.sessionDays(from.toString(), to.toString()), index).stream()
                .map(r -> {
                    SessionRow s = sessions.get(r.sessionId());
                    Task t = index.taskOf(r.sessionId());
                    // 제목은 묶음의 최신 제목 — 이름을 바꾸거나 이어진 세션도 같은 이름으로 보인다
                    return new SessionDayRef(r.sessionId(), r.day(), t.title(), s.project(), t.key(),
                            r.activeSec(), r.prompts(), r.edits(), r.summary(), r.firstAt(), r.lastAt(),
                            u.cost(r.sessionId(), r.day()));
                })
                .sorted(Comparator.comparing(SessionDayRef::day).thenComparing(SessionDayRef::firstAt,
                        Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /** 숨긴 세션과, 이벤트 한두 개만 찍히고 지나간 날(작업 시간·요청·수정 모두 0)은 뺀다 */
    private static List<SessionDayRow> visible(List<SessionDayRow> rows, Index index) {
        return rows.stream().filter(r -> index.taskOfSession().containsKey(r.sessionId()))
                .filter(WorkQueryService::meaningful).toList();
    }

    private static boolean meaningful(SessionDayRow r) {
        return r.activeSec() > 0 || r.prompts() > 0 || r.edits() > 0;
    }

    private static List<EditView> dedupeEdits(List<EditRow> edits) {
        Map<String, EditRow> last = new LinkedHashMap<>();
        edits.forEach(e -> {
            last.remove(e.path());
            last.put(e.path(), e);
        });
        return last.values().stream().map(e -> new EditView(e.at(), e.tool(), e.repo(), relPath(e.path(), e.repo())))
                .toList();
    }

    static String relPath(String path, String repo) {
        if (repo != null) {
            int idx = path.lastIndexOf("/" + repo + "/");
            if (idx >= 0) {
                return path.substring(idx + repo.length() + 2);
            }
        }
        String home = System.getProperty("user.home");
        return path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }

    private static SessionInfo info(SessionRow s) {
        String original = new SessionRow(s.id(), s.customTitle(), s.aiTitle(), s.firstPrompt(), null, s.cwd(),
                s.project(), s.gitBranch(), s.continuedIn(), s.firstAt(), s.lastAt(), s.hidden()).displayTitle();
        return new SessionInfo(s.id(), s.displayTitle(), original, s.titleOverride() != null, s.cwd(), s.project(),
                s.gitBranch(), s.firstAt(), s.lastAt(), s.hidden());
    }

    private static Set<String> ids(Task task) {
        return task.sessions().stream().map(SessionRow::id).collect(Collectors.toSet());
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }
}
