package com.worklog.work;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.worklog.config.ProjectScope;
import com.worklog.config.RoutineSessions;
import com.worklog.config.SettingsService;
import com.worklog.config.WorklogProperties;
import com.worklog.live.LiveSessionService;
import com.worklog.live.LiveSessionService.LiveSession;
import com.worklog.query.Rows.SessionRow;
import com.worklog.query.TaskCatalog;
import com.worklog.query.QueryStore;
import com.worklog.query.Views.SessionDayRef;
import com.worklog.query.WorkQueryService;
import com.worklog.report.Period;
import com.worklog.work.WorkStore.Link;
import com.worklog.work.WorkStore.WorkItem;
import com.worklog.work.WorkViews.Board;
import com.worklog.work.WorkViews.BoardStats;
import com.worklog.work.WorkViews.FromSessionRequest;
import com.worklog.work.WorkViews.ItemDetail;
import com.worklog.work.WorkViews.ItemSession;
import com.worklog.work.WorkViews.ItemSummary;
import com.worklog.work.WorkViews.ItemView;
import com.worklog.work.WorkViews.LinkRequest;
import com.worklog.work.WorkViews.LinkedDay;
import com.worklog.work.WorkViews.SessionCandidate;
import com.worklog.work.WorkViews.TaskWork;
import com.worklog.work.WorkViews.UnassignedSession;
import com.worklog.work.WorkViews.WorkItemRequest;

import lombok.RequiredArgsConstructor;

/**
 * 주간 업무 계획과, 세션×날짜 → 업무 연결.
 * 연결 우선순위: 사용자가 지정(manual) > 세션 고정(pin) > AI(auto) > 미분류.
 */
@Service
@RequiredArgsConstructor
public class WorkService {

    static final Set<String> PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");
    static final Set<String> STATUSES = Set.of("PLANNED", "IN_PROGRESS", "DONE", "ON_HOLD");
    private static final Map<String, Integer> STATUS_ORDER = Map.of("IN_PROGRESS", 0, "PLANNED", 1, "ON_HOLD", 2, "DONE", 3);
    private static final Map<String, Integer> PRIORITY_ORDER = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2);
    /** 연결 없는 날이 같은 작업의 앞선 직접 연결을 이어받는 기간 */
    static final int INHERIT_DAYS = 14;
    /** 계획 외 목록에서 숨기는 짧은 흔적 기준(기간 합계) */
    static final long TRACE_SEC = 180;

    private final WorkStore store;
    private final WorkQueryService query;
    private final QueryStore queryStore;
    private final WorklogProperties props;
    private final LiveSessionService liveSessions;
    private final TaskCatalog catalog;
    private final ProjectScope scope;
    private final RoutineSessions routines;
    private final SettingsService settings;

    // ───────── 보드 ─────────

    public Board board(String weekKey) {
        return board(Period.of("week", weekKey));
    }

    public Board monthBoard(String monthKey) {
        return board(Period.of("month", monthKey));
    }

    /** 주·월 공통. 월이면 그 달에 걸친 주들의 계획을 합치고, 시간·사용량·연결은 그 달 안만 센다 */
    private Board board(Period p) {
        Ctx ctx = ctx();
        List<SessionDayRef> refs = query.sessionDayRefs(p.from(), p.to());
        Resolution res = resolve(refs, store.allLinks(), store.pins(), store.pinStarts(), ctx.index.taskOfSession(), plannedOn());

        List<String> weeks = weeksIn(p);
        Map<Long, WorkItem> plannedMap = new LinkedHashMap<>();
        weeks.forEach(wk -> store.itemsInWeek(wk).forEach(w -> plannedMap.putIfAbsent(w.id(), w)));
        Set<Long> plannedIds = plannedMap.keySet();
        Set<Long> extraIds = new HashSet<>(res.byItem.keySet());
        extraIds.removeAll(plannedIds);
        List<WorkItem> all = new ArrayList<>(plannedMap.values());
        all.addAll(store.items(extraIds));
        List<LinkedDay> unassignedDays = withOrphans(res, all);
        Map<Long, List<String>> weeksByItem = store.weeksByItem(all.stream().map(WorkItem::id).toList());

        List<ItemView> items = all.stream()
                .map(w -> view(w, plannedIds.contains(w.id()), res.byItem.getOrDefault(w.id(), List.of()), ctx,
                        weeksByItem.getOrDefault(w.id(), List.of())))
                .sorted(boardOrder())
                .toList();

        Map<String, List<LinkedDay>> unassignedBySession = new LinkedHashMap<>();
        // 반복 업무 세션(배포 등)은 계획 대상이 아니라 계획 외 목록·연결 시간에서 뺀다(비용 합계엔 남긴다)
        List<LinkedDay> routineDays = unassignedDays.stream().filter(d -> routines.isRoutine(d.title())).toList();
        unassignedDays = unassignedDays.stream().filter(d -> !routines.isRoutine(d.title())).toList();
        unassignedDays.forEach(d -> unassignedBySession.computeIfAbsent(d.sessionId(), k -> new ArrayList<>()).add(d));
        List<UnassignedSession> allUnassigned = unassignedBySession.values().stream()
                .map(days -> {
                    LinkedDay first = days.getFirst();
                    LinkedDay latest = days.getLast();
                    return new UnassignedSession(first.sessionId(), latest.title(), latest.project(), latest.taskKey(),
                            days.stream().mapToLong(LinkedDay::activeSec).sum(),
                            days.stream().allMatch(d -> "auto".equals(d.source())),
                            days.stream().allMatch(d -> "manual".equals(d.source())), days,
                            days.stream().mapToDouble(LinkedDay::costUsd).sum());
                })
                .sorted(Comparator.comparing(UnassignedSession::confirmed)
                        .thenComparing(Comparator.comparingLong(UnassignedSession::activeSec).reversed()))
                .toList();
        // 기간 합계 3분 미만인 흔적(끄기 전 인사, 실수로 친 한 글자 등)은 목록에서 숨긴다. 지금 작업 중인 세션은 그대로
        List<UnassignedSession> unassigned = allUnassigned.stream().filter(u -> !isTrace(u, ctx)).toList();
        int hiddenShort = allUnassigned.size() - unassigned.size();

        boolean isWeek = "week".equals(p.kind());
        String prevKey = isWeek ? Period.weekKey(p.from().minusDays(7)) : YearMonth.from(p.from()).minusMonths(1).toString();
        String nextKey = isWeek ? Period.weekKey(p.to().plusDays(1)) : YearMonth.from(p.from()).plusMonths(1).toString();
        List<ItemView> carryover = isWeek
                ? store.carryoverCandidates(prevKey, p.key()).stream().map(w -> view(w, false, List.of(), null, List.of())).toList()
                : List.of();

        long linkedSec = items.stream().mapToLong(ItemView::activeSec).sum();
        long unassignedSec = allUnassigned.stream().mapToLong(UnassignedSession::activeSec).sum();
        List<ItemView> plannedViews = items.stream().filter(ItemView::planned).toList();
        BoardStats stats = new BoardStats(plannedViews.size(),
                (int) plannedViews.stream().filter(i -> "DONE".equals(i.status())).count(),
                linkedSec + unassignedSec, linkedSec, unassignedSec,
                items.stream().mapToDouble(ItemView::costUsd).sum() + allUnassigned.stream().mapToDouble(UnassignedSession::costUsd).sum()
                        + routineDays.stream().mapToDouble(LinkedDay::costUsd).sum(),
                items.stream().mapToDouble(ItemView::costUsd).sum());

        return new Board(p.kind(), p.key(), targetWeek(p), p.label(), p.from().toString(), p.to().toString(), prevKey,
                nextKey, weeks, store.categories(), items, unassigned, hiddenShort, carryover, stats);
    }

    private static boolean isTrace(UnassignedSession u, Ctx ctx) {
        if (u.activeSec() >= TRACE_SEC || u.confirmed()) {
            return false;
        }
        return u.days().stream().map(LinkedDay::sessionId).distinct()
                .map(ctx.live::get).noneMatch(l -> l != null && "busy".equals(l.status()));
    }

    /** 기간에 걸친 ISO 주들(월이면 4~5개). 주말은 일하지 않아 평일이 그 달에 걸친 주만 센다 */
    static List<String> weeksIn(Period p) {
        LinkedHashSet<String> weeks = new LinkedHashSet<>();
        for (LocalDate d = p.from(); !d.isAfter(p.to()); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                weeks.add(Period.weekKey(d));
            }
        }
        return List.copyOf(weeks);
    }

    /** 새 할 일이 들어갈 주 — 주 보기는 그 주, 월 보기는 이번 달이면 이번 주·지난 달이면 끝 주·다음 달이면 첫 주 */
    private String targetWeek(Period p) {
        if ("week".equals(p.kind())) {
            return p.key();
        }
        LocalDate today = LocalDate.now(props.zone());
        if (today.isBefore(p.from())) {
            return Period.weekKey(p.from());
        }
        return Period.weekKey(today.isAfter(p.to()) ? p.to() : today);
    }

    /** 리포트용 — 기간에 걸친 주들의 계획 업무 + 기간 안에 연결된 작업. 투입 시간은 기간 안만 센다 */
    public PeriodWork periodWork(LocalDate from, LocalDate to) {
        List<SessionDayRef> refs = query.sessionDayRefs(from, to);
        Resolution res = resolve(refs, store.allLinks(), store.pins(), store.pinStarts(), catalog.build().taskOfSession(), plannedOn());
        Set<Long> plannedIds = new HashSet<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(7)) {
            store.itemsInWeek(Period.weekKey(d)).forEach(w -> plannedIds.add(w.id()));
        }
        store.itemsInWeek(Period.weekKey(to)).forEach(w -> plannedIds.add(w.id()));
        Set<Long> ids = new HashSet<>(plannedIds);
        ids.addAll(res.byItem.keySet());
        List<WorkItem> loaded = store.items(ids);
        List<ItemView> items = loaded.stream()
                .map(w -> view(w, plannedIds.contains(w.id()), res.byItem.getOrDefault(w.id(), List.of()), null, List.of()))
                .sorted(boardOrder())
                .toList();
        return new PeriodWork(items, withOrphans(res, loaded).stream().filter(d -> !routines.isRoutine(d.title())).toList());
    }

    public record PeriodWork(List<ItemView> items, List<LinkedDay> unassigned) {
    }

    public ItemDetail detail(long id) {
        WorkItem item = store.item(id).orElseThrow(() -> new IllegalArgumentException("업무가 없음: " + id));
        LocalDate today = LocalDate.now(props.zone());
        List<SessionDayRef> refs = query.sessionDayRefs(LocalDate.parse("2000-01-01"), today);
        Resolution res = resolve(refs, store.allLinks(), store.pins(), store.pinStarts(), catalog.build().taskOfSession(), plannedOn());
        List<LinkedDay> days = new ArrayList<>(res.byItem.getOrDefault(id, List.of()));
        days.sort(Comparator.comparing(LinkedDay::day).reversed());
        int activeDays = (int) days.stream().map(LinkedDay::day).distinct().count();
        return new ItemDetail(view(item, true, days, ctx(), store.weeksOf(id)), store.weeksOf(id), activeDays);
    }

    /** 세션 목록을 할 일별로 묶기 위한 작업 → 할 일 대응. 화면에 보이는 모든 작업이 한 줄씩 */
    public List<TaskWork> taskWork() {
        LocalDate today = LocalDate.now(props.zone());
        TaskCatalog.Index index = catalog.build();
        List<SessionDayRef> refs = query.sessionDayRefs(LocalDate.parse("2000-01-01"), today);
        Resolution res = resolve(refs, store.allLinks(), store.pins(), store.pinStarts(), index.taskOfSession(), plannedOn());
        Map<String, String> lastDay = new HashMap<>();
        Map<String, Long> lastItem = new HashMap<>();
        Map<String, Set<Long>> itemsOf = new HashMap<>();
        res.byItem.forEach((itemId, days) -> {
            for (LinkedDay d : days) {
                itemsOf.computeIfAbsent(d.taskKey(), k -> new HashSet<>()).add(itemId);
                if (lastDay.get(d.taskKey()) == null || d.day().compareTo(lastDay.get(d.taskKey())) > 0) {
                    lastDay.put(d.taskKey(), d.day());
                    lastItem.put(d.taskKey(), itemId);
                }
            }
        });
        Map<Long, WorkItem> items = new HashMap<>();
        store.items(new HashSet<>(lastItem.values())).forEach(w -> items.put(w.id(), w));
        return index.tasks().values().stream().map(t -> {
            WorkItem w = items.get(lastItem.get(t.key()));
            return new TaskWork(t.key(), w == null ? null : w.id(), w == null ? null : w.title(), w == null ? null : w.status(),
                    w == null ? null : w.category(), w == null ? null : lastDay.get(t.key()),
                    itemsOf.getOrDefault(t.key(), Set.of()).size(), routines.isRoutine(t.title()));
        }).toList();
    }

    public List<ItemSummary> itemSummaries() {
        return store.allItems().stream()
                .map(w -> new ItemSummary(w.id(), w.title(), w.category(), w.status()))
                .toList()
                .reversed();
    }

    // ───────── 업무 CRUD ─────────

    @Transactional
    public long create(WorkItemRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("업무명을 입력하세요");
        }
        String status = req.status() == null ? "PLANNED" : check(req.status(), STATUSES, "상태");
        String category = blankToNull(req.category());
        WorkItem draft = new WorkItem(0, req.title().strip(), category,
                req.priority() == null ? "MEDIUM" : check(req.priority(), PRIORITIES, "우선순위"),
                status, date(req.dueDate()), null,
                0,
                null, null, null, memo(req.memo()), subtype(category, req.subtype()));
        long id = store.createItem(draft);
        if (draft.category() != null) {
            store.addCategory(draft.category());
        }
        if (req.weekKey() != null) {
            Period.of("week", req.weekKey());
            store.plan(req.weekKey(), id);
        }
        return id;
    }

    /** null 필드는 그대로, 빈 문자열은 비운다(마감일·카테고리) */
    @Transactional
    public void update(long id, WorkItemRequest req) {
        WorkItem current = store.item(id).orElseThrow(() -> new IllegalArgumentException("업무가 없음: " + id));
        Map<String, Object> cols = new LinkedHashMap<>();
        if (req.title() != null) {
            if (req.title().isBlank()) {
                throw new IllegalArgumentException("업무명은 비울 수 없음");
            }
            cols.put("title", req.title().strip());
        }
        if (req.category() != null) {
            cols.put("category", blankToNull(req.category()));
            if (!req.category().isBlank()) {
                store.addCategory(req.category().strip());
            }
        }
        String category = req.category() != null ? blankToNull(req.category()) : current.category();
        if (req.subtype() != null) {
            cols.put("subtype", subtype(category, req.subtype()));
        } else if (req.category() != null && current.subtype() != null
                && !settings.subtypes().getOrDefault(category, List.of()).contains(current.subtype())) {
            cols.put("subtype", null); // 카테고리를 바꿔 맞지 않게 된 세부 분류
        }
        if (req.priority() != null) {
            cols.put("priority", check(req.priority(), PRIORITIES, "우선순위"));
        }
        if (req.dueDate() != null) {
            cols.put("due_date", date(req.dueDate()));
        }
        if (req.memo() != null) {
            cols.put("memo", memo(req.memo()));
        }
        if (req.status() != null) {
            String status = check(req.status(), STATUSES, "상태");
            cols.put("status", status);
            if ("DONE".equals(status) && !"DONE".equals(current.status())) {
                cols.put("done_at", Instant.now().toString());
            } else if (!"DONE".equals(status)) {
                cols.put("done_at", null);
            }
        }
        store.updateItem(id, cols);
    }

    public void delete(long id) {
        store.deleteItem(id);
    }

    /** 세부 분류 — 비우면 null, 그 카테고리의 목록에 없으면 오류 */
    private String subtype(String category, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> allowed = category == null ? List.of() : settings.subtypes().getOrDefault(category, List.of());
        if (!allowed.contains(value.strip())) {
            throw new IllegalArgumentException("'" + (category == null ? "카테고리 없음" : category) + "' 에 없는 세부 분류: " + value);
        }
        return value.strip();
    }

    public void plan(String weekKey, long id) {
        Period.of("week", weekKey);
        store.item(id).orElseThrow(() -> new IllegalArgumentException("업무가 없음: " + id));
        store.plan(weekKey, id);
    }

    public void unplan(String weekKey, long id) {
        store.unplan(weekKey, id);
    }

    public List<String> categories() {
        return store.categories();
    }

    public void addCategory(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("카테고리 이름을 입력하세요");
        }
        store.addCategory(name.strip());
    }

    // ───────── 연결 ─────────

    /** 사용자가 정한 연결. 업무로 붙이면 그 날짜들이 속한 주 계획에도 넣는다 */
    @Transactional
    public void link(LinkRequest req) {
        if (req.sessionId() == null || req.days() == null || req.days().isEmpty()) {
            throw new IllegalArgumentException("sessionId·days 가 필요함");
        }
        if (req.workItemId() != null) {
            store.item(req.workItemId()).orElseThrow(() -> new IllegalArgumentException("업무가 없음"));
        }
        for (String day : req.days()) {
            LocalDate d = LocalDate.parse(day);
            store.saveManualLink(req.sessionId(), day, req.workItemId());
            if (req.workItemId() != null) {
                store.plan(Period.weekKey(d), req.workItemId());
            }
        }
        if (req.pin() && req.workItemId() != null) {
            store.pin(req.sessionId(), req.workItemId(), LocalDate.now(props.zone()).toString());
        } else if (req.workItemId() == null) {
            store.unpin(req.sessionId());
        }
    }

    /** 미분류 세션을 그대로 새 업무로 올린다 — 제목은 세션 제목, 카테고리는 프로젝트로 추정 */
    @Transactional
    public long fromSession(FromSessionRequest req) {
        var session = queryStore.session(req.sessionId())
                .orElseThrow(() -> new IllegalArgumentException("세션이 없음"));
        long id = create(new WorkItemRequest(session.displayTitle(), guessCategory(session.project()), "MEDIUM",
                "IN_PROGRESS", null, null, req.weekKey(), null));
        if (req.days() != null && !req.days().isEmpty()) {
            link(new LinkRequest(req.sessionId(), req.days(), id, false));
        }
        return id;
    }

    String guessCategory(String project) {
        if (project != null) {
            String p = project.toLowerCase(Locale.ROOT);
            for (String c : store.categories()) {
                if (p.contains(c.toLowerCase(Locale.ROOT))) {
                    return c;
                }
            }
        }
        return store.categories().contains("기타") ? "기타" : null;
    }

    // ───────── 내부 ─────────

    /**
     * 세션×날짜 → 할 일. 같은 작업 묶음(이어진 세션·같은 제목, TaskCatalog)은 연결을 나눠 쓴다 —
     * 컨텍스트가 넘쳐 새 세션으로 이어지거나 이름을 바꿔도 연결이 끊기지 않게.
     * <ol>
     *   <li>manual: 그 날을 직접 정함</li>
     *   <li>pin: 그 세션의 고정, 없으면 같은 묶음 다른 세션의 고정(고정 시작일 이후만)</li>
     *   <li>auto: AI 분류</li>
     *   <li>inherit: 같은 묶음의 앞선 직접 연결(14일 안, 그 주 계획에 든 할 일만) — 연결 없이 생긴 날을 이어받는다</li>
     * </ol>
     * 이어받기·묶음 고정은 저장하지 않고 볼 때마다 계산한다(직접 바꾸면 그쪽이 이긴다).
     */
    Resolution resolve(List<SessionDayRef> refs, List<Link> links, Map<String, Long> pins, Map<String, String> pinStarts,
                       Map<String, String> taskOfSession, BiPredicate<Long, String> plannedOn) {
        Map<String, Link> byKey = new HashMap<>();
        links.forEach(l -> byKey.put(l.key(), l));
        // 묶음별 고정(시작일 늦은 것 우선)과 직접 연결 이력(날짜순)
        Map<String, List<String>> pinnedByTask = new HashMap<>();
        pins.keySet().forEach(sid -> pinnedByTask.computeIfAbsent(taskOfSession.getOrDefault(sid, sid), k -> new ArrayList<>()).add(sid));
        pinnedByTask.values().forEach(l -> l.sort(Comparator.comparing((String sid) -> Objects.toString(pinStarts.get(sid), "")).reversed()));
        Map<String, TreeMap<String, Long>> manualByTask = new HashMap<>();
        for (Link l : links) {
            if ("manual".equals(l.source()) && l.workItemId() != null) {
                manualByTask.computeIfAbsent(taskOfSession.getOrDefault(l.sessionId(), l.sessionId()), k -> new TreeMap<>())
                        .put(l.day(), l.workItemId());
            }
        }

        Map<Long, List<LinkedDay>> byItem = new LinkedHashMap<>();
        List<LinkedDay> unassigned = new ArrayList<>();
        for (SessionDayRef r : refs) {
            String task = taskOfSession.getOrDefault(r.sessionId(), r.sessionId());
            Link link = byKey.get(r.key());
            Long item = null;
            String source = null;
            if (link != null && "manual".equals(link.source())) {
                item = link.workItemId();
                source = "manual";
            } else if (pins.containsKey(r.sessionId()) && pinActive(pinStarts.get(r.sessionId()), r.day())) {
                item = pins.get(r.sessionId());
                source = "pin";
            } else if ((item = taskPin(pinnedByTask.get(task), pins, pinStarts, r.day())) != null) {
                source = "pin";
            } else if (link != null) {
                item = link.workItemId();
                source = "auto";
            } else {
                Map.Entry<String, Long> prev = Optional.ofNullable(manualByTask.get(task))
                        .map(m -> m.lowerEntry(r.day())).orElse(null);
                // 그 주 계획에 든 할 일로만 — 지난 할 일을 이번 주 보드로 끌어오지 않는다
                if (prev != null && !LocalDate.parse(prev.getKey()).isBefore(LocalDate.parse(r.day()).minusDays(INHERIT_DAYS))
                        && plannedOn.test(prev.getValue(), r.day())) {
                    item = prev.getValue();
                    source = "inherit";
                }
            }
            LinkedDay day = new LinkedDay(r.key(), r.sessionId(), r.day(), r.title(), r.project(), r.taskKey(),
                    r.activeSec(), r.prompts(), r.summary(), source, r.costUsd());
            if (item != null) {
                byItem.computeIfAbsent(item, k -> new ArrayList<>()).add(day);
            } else {
                unassigned.add(day);
            }
        }
        return new Resolution(byItem, unassigned);
    }

    /** (할 일, 날짜) → 그 날이 속한 주 계획에 들어 있나. 주별로 한 번만 읽는다 */
    private BiPredicate<Long, String> plannedOn() {
        Map<String, Set<Long>> byWeek = new HashMap<>();
        return (item, day) -> byWeek.computeIfAbsent(Period.weekKey(LocalDate.parse(day)),
                wk -> store.itemsInWeek(wk).stream().map(WorkItem::id).collect(Collectors.toSet())).contains(item);
    }

    private static Long taskPin(List<String> pinned, Map<String, Long> pins, Map<String, String> pinStarts, String day) {
        if (pinned == null) {
            return null;
        }
        for (String sid : pinned) {
            if (pinActive(pinStarts.get(sid), day)) {
                return pins.get(sid);
            }
        }
        return null;
    }

    record Resolution(Map<Long, List<LinkedDay>> byItem, List<LinkedDay> unassigned) {
    }

    /** 고정 시작일 이후(당일 포함)만. 시작일이 없으면 예전 데이터라 그대로 적용 */
    private static boolean pinActive(String from, String day) {
        return from == null || day.compareTo(from) >= 0;
    }

    /** 지워진 업무를 가리키는 연결은 미분류로 돌린다 — 작업이 화면에서 사라지지 않게 */
    private static List<LinkedDay> withOrphans(Resolution res, List<WorkItem> loaded) {
        Set<Long> known = loaded.stream().map(WorkItem::id).collect(Collectors.toSet());
        List<LinkedDay> days = new ArrayList<>(res.unassigned);
        res.byItem.forEach((id, list) -> {
            if (!known.contains(id)) {
                days.addAll(list);
            }
        });
        days.sort(Comparator.comparing(LinkedDay::day));
        return days;
    }

    /** ctx 가 null 이면(리포트·이월 후보) 세션 묶음은 비워 둔다 */
    private static ItemView view(WorkItem w, boolean planned, List<LinkedDay> days, Ctx ctx, List<String> weeks) {
        return new ItemView(w.id(), w.title(), w.category(), w.subtype(), w.priority(), w.status(), w.dueDate(),
                w.createdAt(), w.updatedAt(), w.doneAt(), w.memo(), planned,
                days.stream().mapToLong(LinkedDay::activeSec).sum(), days,
                days.stream().mapToDouble(LinkedDay::costUsd).sum(),
                ctx == null ? List.of() : ctx.sessionsOf(w.id(), days), weeks);
    }

    // ───────── 세션 직접 연결 ─────────

    /**
     * 할 일에 세션을 붙인다 — 그 세션의 이번 주 작업을 이 할 일로 옮기고, 앞으로 그 세션의 작업도 이 할 일로(고정).
     * 아직 수집 전인 막 연 세션도 고정만 먼저 걸어 둔다.
     */
    @Transactional
    public void attachSession(long itemId, String sessionId, Period period) {
        store.item(itemId).orElseThrow(() -> new IllegalArgumentException("할 일이 없음: " + itemId));
        // 이 기간의 지난 날들은 아래에서 직접 연결로 남기고, 고정은 오늘부터 — 그 전 작업으로 소급하지 않는다
        store.pin(sessionId, itemId, LocalDate.now(props.zone()).toString());
        String task = catalog.build().taskOfSession().getOrDefault(sessionId, sessionId);
        for (SessionDayRef r : query.sessionDayRefs(period.from(), period.to())) {
            // 같은 작업 묶음(이어진 앞 세션 등)의 이 기간 작업도 함께
            if (r.sessionId().equals(sessionId) || task.equals(r.taskKey())) {
                store.saveManualLink(r.sessionId(), r.day(), itemId);
                store.plan(Period.weekKey(LocalDate.parse(r.day())), itemId);
            }
        }
        if ("week".equals(period.kind())) {
            store.plan(period.key(), itemId);
        }
    }

    /** 떼어 낼 때도 묶음 전체 — 하나만 떼면 남은 세션의 연결을 다시 이어받아 돌아온다 */
    @Transactional
    public void detachSession(long itemId, String sessionId) {
        TaskCatalog.Index index = catalog.build();
        String task = index.taskOfSession().getOrDefault(sessionId, sessionId);
        store.unlinkSession(sessionId, itemId);
        index.taskOfSession().forEach((sid, t) -> {
            if (t.equals(task)) {
                store.unlinkSession(sid, itemId);
            }
        });
    }

    /** "세션 추가" 목록 — 지금 열린 세션 먼저, 그다음 최근 7일 안에 쓴 세션 */
    public List<SessionCandidate> candidates(Period week) {
        Ctx ctx = ctx();
        Map<String, Long> weekSec = new HashMap<>();
        query.sessionDayRefs(week.from(), week.to())
                .forEach(r -> weekSec.merge(r.sessionId(), r.activeSec(), Long::sum));
        Map<Long, String> itemTitles = new HashMap<>();
        store.items(new HashSet<>(ctx.pins.values())).forEach(w -> itemTitles.put(w.id(), w.title()));

        List<SessionCandidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> seenTasks = new HashSet<>();
        // 같은 작업 묶음을 연 프로세스가 여럿이어도 한 줄 — 가장 최근에 움직인 것
        List<LiveSession> liveRecent = ctx.live.values().stream()
                .sorted(Comparator.comparing(LiveSession::statusUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        for (LiveSession l : liveRecent) {
            SessionRow row = ctx.rows.get(l.sessionId());
            seen.add(l.sessionId());
            if ((row != null && row.hidden()) || routines.isRoutine(ctx.title(l.sessionId()))
                    || !seenTasks.add(ctx.index.taskOfSession().getOrDefault(l.sessionId(), l.sessionId()))) {
                continue;
            }
            result.add(candidate(l.sessionId(), row, l, ctx, weekSec, itemTitles));
        }
        Instant since = Instant.now().minus(Duration.ofDays(7));
        ctx.rows.values().stream()
                .filter(r -> !r.hidden() && !seen.contains(r.id()) && r.lastAt() != null && Instant.parse(r.lastAt()).isAfter(since))
                .filter(r -> !seenTasks.contains(ctx.index.taskOfSession().getOrDefault(r.id(), r.id())))
                .filter(r -> !routines.isRoutine(ctx.title(r.id())))
                .sorted(Comparator.comparing(SessionRow::lastAt).reversed())
                .limit(30)
                .forEach(r -> result.add(candidate(r.id(), r, null, ctx, weekSec, itemTitles)));
        return result;
    }

    private SessionCandidate candidate(String sid, SessionRow row, LiveSession live, Ctx ctx, Map<String, Long> weekSec,
                                       Map<Long, String> itemTitles) {
        Long pinned = ctx.pins.get(sid);
        String lastAt = live != null && live.statusUpdatedAt() != null ? live.statusUpdatedAt().toString()
                : row == null ? null : row.lastAt();
        return new SessionCandidate(sid, ctx.title(sid), row != null ? row.project() : live == null ? null : scope.labelForCwd(live.cwd()).orElse(null),
                ctx.index.taskOfSession().get(sid), live != null, live != null && "busy".equals(live.status()),
                live == null ? null : live.kind(), lastAt, weekSec.getOrDefault(sid, 0L), pinned,
                pinned == null ? null : itemTitles.get(pinned));
    }

    private Ctx ctx() {
        Map<String, SessionRow> rows = new HashMap<>();
        queryStore.sessions().forEach(r -> rows.put(r.id(), r));
        Map<String, LiveSession> live = new LinkedHashMap<>();
        liveSessions.list().stream()
                .filter(l -> rows.containsKey(l.sessionId()) || scope.labelForCwd(l.cwd()).isPresent())
                .forEach(l -> live.putIfAbsent(l.sessionId(), l));
        return new Ctx(store.pins(), live, catalog.build(), rows);
    }

    /** 보드 한 번 그리는 동안 쓰는 세션 정보 묶음 */
    record Ctx(Map<String, Long> pins, Map<String, LiveSession> live, TaskCatalog.Index index,
               Map<String, SessionRow> rows) {

        /** 이름을 바꾸거나 새 세션으로 이어졌으면 묶음의 최신 제목 */
        String title(String sid) {
            TaskCatalog.Task t = index.taskOf(sid);
            if (t != null) {
                return t.title();
            }
            SessionRow r = rows.get(sid);
            if (r != null) {
                return r.displayTitle();
            }
            LiveSession l = live.get(sid);
            return l != null && l.name() != null ? l.name() : "새 세션";
        }

        /** 할 일에 붙은 세션 — 같은 작업 묶음(이어진 세션 등)은 한 줄로. 대표 id 는 열려 있는 것, 없으면 최근 세션 */
        List<ItemSession> sessionsOf(long itemId, List<LinkedDay> days) {
            Map<String, List<LinkedDay>> byTask = new LinkedHashMap<>();
            Map<String, Set<String>> members = new LinkedHashMap<>();
            days.forEach(d -> {
                String t = taskKey(d.sessionId());
                byTask.computeIfAbsent(t, k -> new ArrayList<>()).add(d);
                members.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(d.sessionId());
            });
            pins.forEach((sid, item) -> {
                if (item == itemId) {
                    String t = taskKey(sid);
                    byTask.computeIfAbsent(t, k -> new ArrayList<>());
                    members.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(sid);
                }
            });
            return byTask.entrySet().stream().map(e -> {
                Set<String> sids = members.get(e.getKey());
                List<LinkedDay> ds = e.getValue();
                Comparator<String> recent = Comparator.comparing((String sid) -> {
                    SessionRow r = rows.get(sid);
                    return r == null || r.lastAt() == null ? "" : r.lastAt();
                });
                String rep = sids.stream().filter(live::containsKey).max(recent)
                        .orElseGet(() -> sids.stream().max(recent).orElseThrow());
                LiveSession l = sids.stream().map(live::get).filter(Objects::nonNull).findFirst().orElse(null);
                SessionRow r = rows.get(rep);
                return new ItemSession(rep, title(rep), r == null ? null : r.project(), index.taskOfSession().get(rep),
                        sids.stream().anyMatch(sid -> Objects.equals(pins.get(sid), itemId)), l != null,
                        sids.stream().map(live::get).anyMatch(x -> x != null && "busy".equals(x.status())),
                        ds.stream().mapToLong(LinkedDay::activeSec).sum(), ds.stream().mapToDouble(LinkedDay::costUsd).sum(), ds);
            }).sorted(Comparator.comparing((ItemSession x) -> !x.live()).thenComparing(ItemSession::activeSec, Comparator.reverseOrder()))
                    .toList();
        }

        private String taskKey(String sid) {
            return index.taskOfSession().getOrDefault(sid, sid);
        }
    }

    private static Comparator<ItemView> boardOrder() {
        return Comparator.comparing((ItemView i) -> !i.planned())
                .thenComparing(i -> STATUS_ORDER.getOrDefault(i.status(), 9))
                .thenComparing(i -> PRIORITY_ORDER.getOrDefault(i.priority(), 9))
                .thenComparing(i -> i.dueDate() == null ? "9999" : i.dueDate())
                .thenComparingLong(ItemView::id);
    }

    private static String check(String v, Set<String> allowed, String name) {
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(name + " 값이 잘못됨: " + v);
        }
        return v;
    }


    private static String date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.strip()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("마감일 형식은 yyyy-MM-dd");
        }
    }

    private static final int MAX_MEMO = 20_000;

    /** 메모는 줄바꿈·앞 공백을 살린다. 끝 공백만 정리하고 비면 null */
    private static String memo(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.stripTrailing();
        if (v.length() > MAX_MEMO) {
            throw new IllegalArgumentException("메모는 " + MAX_MEMO + "자까지");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
