package com.worklog.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worklog.agent.ObserverCalc.Data;
import com.worklog.agent.ObserverViews.AddedLoad;
import com.worklog.agent.ObserverViews.Insight;
import com.worklog.agent.ObserverViews.InsightRequest;
import com.worklog.agent.ObserverViews.MetricDef;
import com.worklog.agent.ObserverViews.Observer;
import com.worklog.agent.ObserverViews.Outcome;
import com.worklog.agent.ObserverViews.OutcomeWeek;
import com.worklog.agent.ObserverViews.Outcomes;
import com.worklog.agent.ObserverViews.Pending;
import com.worklog.agent.ObserverViews.PendingSession;
import com.worklog.agent.ObserverViews.Snapshot;
import com.worklog.agent.ObserverViews.SourceSession;
import com.worklog.agent.ObserverViews.Target;
import com.worklog.agent.ObserverViews.TrendPoint;
import com.worklog.collect.CollectScheduler;
import com.worklog.config.ProjectScope;
import com.worklog.query.TaskCatalog;
import com.worklog.config.WorklogProperties;
import com.worklog.report.Period;
import com.worklog.summary.ClaudeCli;
import com.worklog.summary.JobService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 에이전트 옵저버: 주별 지표·병목 → 개선 제안 → md 도입 → 도입 전후 비교.
 * <p>
 * 효과 측정: 도입한 날 직전 14일을 기준값으로 고정하고, 도입한 날부터 14일(그 전이면 오늘까지)을 같은 범위(카테고리)·같은 지표로
 * 다시 잰다. 일한 날이 3일 안 되면 "측정 중". 10% 넘게 좋아지면 개선, 10% 넘게 나빠지면 악화.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObserverService {

    private static final int TREND_WEEKS = 8;
    private static final int TREND_MONTHS = 6;
    private static final int WINDOW_DAYS = 14;
    private static final int MIN_DAYS = 3;
    private static final double SIGNIFICANT = 0.10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObserverCalc calc;
    private final AgentStore store;
    private final InsightTargets targets;
    private final JdbcClient jdbc;
    private final WorklogProperties props;
    private final ClaudeCli claude;
    private final JobService jobs;
    private final CollectScheduler collector;
    private final ProjectScope scope;
    private final TaskCatalog catalog;
    private final RuleLoadService ruleLoad;

    public Observer observer(String weekKey, String scope) {
        return observer("week", weekKey, scope);
    }

    /**
     * kind = week | month | year. 비교 = 바로 앞 같은 기간(지난주·지난달·작년),
     * 추이 = 주 보기 최근 8주, 월 보기 최근 6개월, 연 보기 그 해 12개월
     */
    public Observer observer(String kind, String key, String scope) {
        Period p = Period.of(kind, key);
        Period before = p.previous();
        String sc = normalizeScope(scope);
        List<Period> points = trendPoints(p);
        LocalDate loadFrom = points.getFirst().from().isBefore(before.from()) ? points.getFirst().from() : before.from();
        Data all = calc.load(loadFrom, p.to());
        Data cur = calc.slice(all, p.from(), p.to(), sc);
        Data prev = calc.slice(all, before.from(), before.to(), sc);

        List<InsightRow> insights = insightRows();
        List<TrendPoint> trend = new ArrayList<>();
        for (Period t : points) {
            List<Long> adopted = insights.stream()
                    .filter(r -> r.adoptedAt != null && !day(r.adoptedAt).isBefore(t.from()) && !day(r.adoptedAt).isAfter(t.to()))
                    .map(r -> r.id).toList();
            trend.add(new TrendPoint(t.key(), shortLabel(t), calc.snapshot(calc.slice(all, t.from(), t.to(), sc)).metrics(), adopted));
        }
        return new Observer(kind, key, p.label(), before.key(), p.next().key(),
                sc, store.firstDay().orElse(null), metricDefs(), calc.snapshot(cur), calc.snapshot(prev),
                calc.tools(cur), calc.slowCommands(cur, 8), calc.errors(cur, 8), calc.corrections(cur, 10),
                calc.rework(cur, 4, 8), calc.sessions(cur, 6), calc.models(cur), trend);
    }

    public static List<MetricDef> metricDefs() {
        return Arrays.stream(Metric.values()).map(m -> new MetricDef(m.key, m.label, m.unit, m.lowerIsBetter, m.description)).toList();
    }

    public List<Target> targets() {
        return targets.list();
    }

    // ───────── 개선 제안 ─────────

    public List<Insight> insights() {
        List<InsightRow> rows = insightRows();
        LocalDate today = LocalDate.now(props.zone());
        // 측정 중인 것들을 한 번에 계산하려고 가장 이른 도입일부터 한 번만 읽는다
        Optional<LocalDate> earliest = rows.stream().filter(r -> "adopted".equals(r.status) && r.adoptedAt != null)
                .map(r -> day(r.adoptedAt)).min(Comparator.naturalOrder());
        Data data = earliest.map(e -> calc.load(e, today)).orElse(null);
        Map<String, Target> tmap = new HashMap<>();
        targets.list().forEach(t -> tmap.put(t.key(), t));
        Map<String, Integer> loads = new HashMap<>();
        ruleLoad.load().forEach(l -> loads.put(l.target(), l.estTokens()));
        return rows.stream().map(r -> view(r, data, today, tmap, loads)).toList();
    }

    private Insight view(InsightRow r, Data data, LocalDate today, Map<String, Target> tmap, Map<String, Integer> loads) {
        Double after = r.result;
        String afterFrom = r.resultFrom;
        String afterTo = r.resultTo;
        int days = 0;
        String verdict = r.verdict;
        if ("adopted".equals(r.status) && data != null && r.metric != null) {
            Window w = afterWindow(day(r.adoptedAt), today);
            Data slice = calc.slice(data, w.from, w.to, r.scope);
            after = calc.snapshot(slice).metrics().get(r.metric);
            afterFrom = w.from.toString();
            afterTo = w.to.toString();
            days = calc.workedDays(slice);
            verdict = days < MIN_DAYS ? "측정 중" : verdict(r.metric, r.baseline, after);
        }
        Target t = tmap.get(r.target);
        return new Insight(r.id, r.createdAt, r.weekKey, r.source, r.title, r.problem, r.metric, r.scope, r.target,
                t == null ? r.target : t.label(), t == null ? null : t.path(), r.snippet, r.status, r.adoptedAt, r.appliedPath,
                r.target != null && targets.applied(r.id, r.target), r.baseline, r.baselineFrom, r.baselineTo, after,
                afterFrom, afterTo, days, delta(r.baseline, after), verdict, r.closedAt, r.note, r.tier, r.tierReason, r.effect,
                r.sessions, addedLoad(r, loads), r.signals);
    }

    /** 넣을 곳 무게(한 번에 읽어 둠) + 이 규칙이 더하는 양 */
    private AddedLoad addedLoad(InsightRow r, Map<String, Integer> loads) {
        String[] t = InsightTargets.addedText(r.id, r.target, r.title, r.problem, r.snippet);
        return new AddedLoad(RuleLoadService.estTokens(t[0]), t[0].length(), RuleLoadService.estTokens(t[1]),
                r.target == null ? 0 : loads.getOrDefault(r.target, 0));
    }

    public long create(InsightRequest req, String source, String weekKey) {
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("제목을 적으세요");
        }
        validate(req);
        return jdbc.sql("""
                INSERT INTO agent_insight (created_at, week_key, source, title, problem, metric, scope, target, snippet, status, note,
                                           tier, tier_reason, effect, sessions, signals)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'proposed', ?, ?, ?, ?, ?, ?) RETURNING id
                """).params(Instant.now().toString(), weekKey == null ? Period.weekKey(LocalDate.now(props.zone())) : weekKey,
                source, req.title().strip(), blankToNull(req.problem()), blankToNull(req.metric()), normalizeScope(req.scope()),
                blankToNull(req.target()), blankToNull(req.snippet()), blankToNull(req.note()), tierOf(req.tier()),
                blankToNull(req.tierReason()), blankToNull(req.effect()), sessionsJson(req.sessions()), signalsJson(req.signals()))
                .query(Long.class).single();
    }

    public void update(long id, InsightRequest req) {
        InsightRow r = row(id);
        validate(req);
        boolean locked = !"proposed".equals(r.status) && !"rejected".equals(r.status);
        if (locked && (req.metric() != null || req.scope() != null)) {
            throw new IllegalStateException("도입한 뒤에는 지표·범위를 바꿀 수 없습니다 (기준값이 고정됨)");
        }
        set(id, "title", req.title() == null ? null : req.title().strip());
        set(id, "problem", req.problem());
        set(id, "metric", req.metric());
        set(id, "scope", req.scope() == null ? null : normalizeScopeForWrite(req.scope()));
        set(id, "target", req.target());
        set(id, "snippet", req.snippet());
        set(id, "note", req.note());
        set(id, "tier", req.tier() == null ? null : tierOf(req.tier()));
        set(id, "tier_reason", req.tierReason());
        if (req.signals() != null) {
            jdbc.sql("UPDATE agent_insight SET signals = ? WHERE id = ?").params(signalsJson(req.signals()), id).update();
        }
        set(id, "effect", req.effect());
        if (req.status() != null) {
            if (!List.of("proposed", "rejected").contains(req.status())) {
                throw new IllegalArgumentException("status 는 proposed·rejected 만 직접 바꿀 수 있습니다");
            }
            if (!"proposed".equals(r.status) && !"rejected".equals(r.status)) {
                throw new IllegalStateException("도입한 제안은 먼저 되돌리세요");
            }
            set(id, "status", req.status());
        }
    }

    public void delete(long id) {
        InsightRow r = row(id);
        if ("adopted".equals(r.status) && r.target != null && targets.applied(id, r.target)) {
            throw new IllegalStateException("파일에 들어가 있는 제안입니다 — 먼저 되돌리세요");
        }
        jdbc.sql("DELETE FROM agent_insight WHERE id = ?").param(id).update();
    }

    /** 필수 단계 제안을 한꺼번에 도입(지표·넣을 곳·문안이 갖춰진 것만). @return 도입한 개수 */
    public int adoptMust(boolean write) throws IOException {
        int n = 0;
        for (InsightRow r : insightRows()) {
            if ("proposed".equals(r.status) && "must".equals(r.tier) && r.metric != null && r.target != null && r.snippet != null) {
                adopt(r.id, write);
                n++;
            }
        }
        return n;
    }

    /**
     * 아직 도입 안 한 제안을 규칙 하나씩으로 쪼개고 단계·이유·효과를 다시 매긴다(묶여 있던 예전 제안 정리용).
     * 원래 제안은 지우고 새 제안으로 바꾼다 — 근거·지표·범위·넣을 곳은 원래 것을 이어받는다
     */
    public JobService.Job submitReclassify() {
        return jobs.submit("observer:reclassify", "개선 제안 나눠 정리", progress -> {
            List<InsightRow> olds = insightRows().stream().filter(r -> "proposed".equals(r.status)).toList();
            if (olds.isEmpty()) {
                return "정리할 제안 없음";
            }
            StringBuilder in = new StringBuilder();
            for (InsightRow r : olds) {
                in.append("### id ").append(r.id).append(" — ").append(r.title).append('\n')
                        .append("근거: ").append(r.problem == null ? "-" : r.problem).append('\n')
                        .append("지표: ").append(r.metric).append(" · 넣을 곳: ").append(r.target).append('\n')
                        .append("문안:\n").append(r.snippet == null ? "-" : r.snippet).append("\n\n");
            }
            String prompt = """
                    아래는 AI 코딩 에이전트 사용 개선 제안들이다. 하나에 규칙이 여러 개 묶여 있어 사용자가 넣을지 고민한다.
                    제안마다 문안의 규칙을 **규칙 하나씩** 따로 나누고(같은 말 반복은 합침), 각각 단계를 매긴다.
                    - tier: must(필수: 실제 사고·권한 거절·같은 정정이 반복됐고 부작용 거의 없음) · recommend(권장: 효과는 숫자로 보이나
                      특정 저장소·상황 한정) · optional(선택: 작업 방식·취향, 상황에 따라 손해일 수 있음). 애매하면 한 단계 낮게.
                    - title: 그 규칙만 가리키는 한 줄 제목. snippet: 규칙 문안 1줄(마크다운 불릿 "- "로 시작, 원문의 명령·경로는 살림).
                    - tierReason: 단계 이유 한 줄(숫자 포함, 30자 안팎). effect: 넣으면 무엇이 얼마나 줄지 한 줄.
                    - from: 원래 제안 id(근거·지표·넣을 곳을 이어받는다).
                    출력은 JSON 배열 하나만(설명·코드블록 없이):
                    [{"from":1,"title":"…","tier":"must|recommend|optional","tierReason":"…","effect":"…","snippet":"- …"}]

                    %s""".formatted(in);
            progress.update("제안 나누는 중");
            String out = claude.ask(prompt);
            int s0 = out.indexOf('[');
            int e0 = out.lastIndexOf(']');
            if (s0 < 0 || e0 <= s0) {
                throw new IOException("나눈 제안을 JSON 으로 받지 못함");
            }
            Map<Long, InsightRow> byId = new HashMap<>();
            olds.forEach(r -> byId.put(r.id, r));
            Set<Long> replaced = new HashSet<>();
            int count = 0;
            for (JsonNode n : MAPPER.readTree(out.substring(s0, e0 + 1))) {
                InsightRow from = byId.get(n.path("from").asLong(-1));
                String title = n.path("title").asText("").strip();
                if (from == null || title.isEmpty()) {
                    continue;
                }
                create(new InsightRequest(title, from.problem, from.metric, from.scope, from.target, n.path("snippet").asText(null),
                        from.note, null, tierOf(n.path("tier").asText(null)), n.path("tierReason").asText(null),
                        n.path("effect").asText(null), from.sessions, from.signals), from.source, from.weekKey);
                replaced.add(from.id);
                count++;
            }
            if (count == 0) {
                throw new IOException("나눈 제안이 없음 — 원래 제안은 그대로 둠");
            }
            // 새 제안으로 바뀐 원래 제안만 지운다
            replaced.forEach(id -> jdbc.sql("DELETE FROM agent_insight WHERE id = ?").param(id).update());
            return olds.size() + "개 → " + count + "개로 나눔";
        });
    }

    /** 도입: 기준값(직전 14일)을 고정하고, write 면 md 에 실제로 적는다 */
    public Insight adopt(long id, boolean write) throws IOException {
        InsightRow r = row(id);
        if (!"proposed".equals(r.status) && !"rejected".equals(r.status)) {
            throw new IllegalStateException("이미 도입한 제안입니다");
        }
        LocalDate today = LocalDate.now(props.zone());
        LocalDate bFrom = today.minusDays(WINDOW_DAYS);
        LocalDate bTo = today.minusDays(1);
        Double baseline = null;
        if (r.metric != null) {
            baseline = calc.snapshot(calc.slice(calc.load(bFrom, bTo), bFrom, bTo, r.scope)).metrics().get(r.metric);
        }
        String applied = null;
        if (write) {
            Path p = targets.apply(id, r.target, r.title, r.problem, r.snippet);
            applied = p.toString();
        }
        jdbc.sql("""
                UPDATE agent_insight SET status = 'adopted', adopted_at = ?, applied_path = ?, baseline = ?, baseline_from = ?,
                    baseline_to = ?, result = NULL, result_from = NULL, result_to = NULL, verdict = NULL, closed_at = NULL
                WHERE id = ?
                """).params(Instant.now().toString(), applied, baseline, bFrom.toString(), bTo.toString(), id).update();
        return insights().stream().filter(i -> i.id() == id).findFirst().orElseThrow();
    }

    /** 이미 손으로 넣어 둔 제안을 파일에 적기만 */
    public void writeToFile(long id) throws IOException {
        InsightRow r = row(id);
        Path p = targets.apply(id, r.target, r.title, r.problem, r.snippet);
        set(id, "applied_path", p.toString());
    }

    /** 파일에서 빼고 제안 상태로 — 기준값·측정은 버린다 */
    public void revert(long id) throws IOException {
        InsightRow r = row(id);
        if (r.target != null) {
            targets.revert(id, r.target);
        }
        jdbc.sql("""
                UPDATE agent_insight SET status = 'proposed', adopted_at = NULL, applied_path = NULL, baseline = NULL,
                    baseline_from = NULL, baseline_to = NULL, result = NULL, result_from = NULL, result_to = NULL,
                    verdict = NULL, closed_at = NULL WHERE id = ?
                """).param(id).update();
    }

    /** 지금 측정값으로 결과를 확정한다 */
    public void close(long id) {
        Insight v = insights().stream().filter(i -> i.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("없는 제안: " + id));
        if (!"adopted".equals(v.status())) {
            throw new IllegalStateException("도입한 제안만 확정할 수 있습니다");
        }
        String verdict = v.afterDays() < MIN_DAYS ? "판단 보류" : v.verdict();
        jdbc.sql("""
                UPDATE agent_insight SET status = 'done', result = ?, result_from = ?, result_to = ?, verdict = ?, closed_at = ?
                WHERE id = ?
                """).params(v.after(), v.afterFrom(), v.afterTo(), verdict, Instant.now().toString(), id).update();
    }

    /** 확정을 풀고 다시 측정 */
    public void reopen(long id) {
        if (!"done".equals(row(id).status)) {
            throw new IllegalStateException("확정한 제안만 다시 열 수 있습니다");
        }
        jdbc.sql("UPDATE agent_insight SET status = 'adopted', result = NULL, result_from = NULL, result_to = NULL, verdict = NULL, closed_at = NULL WHERE id = ?")
                .param(id).update();
    }

    // ───────── AI 제안 ─────────

    // 기간이 아니라 세션 단위로 본다: 세션마다 "어디까지 봤는지"를 남기고, 그 뒤에 생긴 기록만 다음에 본다.
    // 예전에 봤던 세션이 이어지면 이어진 부분만 다시 본다.

    private static final int SCAN_LOOKBACK_DAYS = 30;
    private static final int BASELINE_DAYS = 28;

    /** 아직 개선점을 안 본 세션(최근 30일, 새로 생긴 부분 기준) */
    public Pending pending() {
        LocalDate today = LocalDate.now(props.zone());
        return new Pending(newParts(calc.load(today.minusDays(SCAN_LOOKBACK_DAYS), today)).sessions());
    }

    private record NewParts(Data data, List<PendingSession> sessions) {
    }

    private NewParts newParts(Data all) {
        Map<String, Instant> marks = scanMarks();
        Instant floor = scanFloor();
        Data d = calc.sliceAfter(all, sid -> marks.getOrDefault(sid, floor));
        Set<String> ids = calc.activeSessions(d);
        Map<String, Integer> prompts = new HashMap<>();
        Map<String, Integer> errors = new HashMap<>();
        Map<String, Integer> corrections = new HashMap<>();
        d.prompts().forEach(x -> {
            prompts.merge(x.sessionId(), 1, Integer::sum);
            if (ObserverCalc.isCorrection(x.text())) {
                corrections.merge(x.sessionId(), 1, Integer::sum);
            }
        });
        d.tools().stream().filter(c -> "error".equals(c.status())).forEach(c -> errors.merge(c.sessionId(), 1, Integer::sum));
        List<PendingSession> list = ids.stream().map(id -> {
            var row = d.sessions().get(id);
            return new PendingSession(id, d.taskOf().getOrDefault(id, id), row == null ? id : row.displayTitle(),
                    row == null ? null : row.project(), row == null ? null : row.lastAt(), prompts.getOrDefault(id, 0),
                    errors.getOrDefault(id, 0), corrections.getOrDefault(id, 0), marks.containsKey(id));
        }).sorted(Comparator.comparing((PendingSession x) -> x.lastAt() == null ? "" : x.lastAt()).reversed()).toList();
        Data only = calc.onlySessions(d, ids);
        return new NewParts(only, list);
    }

    private Map<String, Instant> scanMarks() {
        Map<String, Instant> out = new HashMap<>();
        jdbc.sql("SELECT session_id, scanned_until FROM insight_scan").query((rs, i) -> {
            Instant at = ObserverCalc.parse(rs.getString(2));
            if (at != null) {
                out.put(rs.getString(1), at);
            }
            return null;
        }).list();
        return out;
    }

    /** 한 번도 안 본 세션의 기준 — 마지막 AI 제안 시각(그 전 기록은 기간 제안 때 이미 봤다), 없으면 30일 전 */
    private Instant scanFloor() {
        Instant limit = Instant.now().minus(java.time.Duration.ofDays(SCAN_LOOKBACK_DAYS));
        Instant last = jdbc.sql("SELECT MAX(created_at) FROM agent_insight WHERE source = 'ai'").query(String.class).optional()
                .map(ObserverCalc::parse).orElse(null);
        return last != null && last.isAfter(limit) ? last : limit;
    }

    private void markScanned(Set<String> ids, Data d) {
        String now = Instant.now().toString();
        for (String id : ids) {
            var row = d.sessions().get(id);
            String until = row != null && row.lastAt() != null ? row.lastAt() : now;
            jdbc.sql("""
                    INSERT INTO insight_scan (session_id, scanned_until, scanned_at) VALUES (?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET scanned_until = excluded.scanned_until, scanned_at = excluded.scanned_at
                    """).params(id, until, now).update();
        }
    }

    /** 새로 생긴 세션(이어진 부분 포함) 전부에서 개선점을 찾는다 */
    public JobService.Job submitScan() {
        return jobs.submit("observer:scan", "새 세션에서 개선점 찾기", progress -> {
            progress.update("최신 기록 수집 중");
            collector.collectAndWait();
            LocalDate today = LocalDate.now(props.zone());
            NewParts np = newParts(calc.load(today.minusDays(SCAN_LOOKBACK_DAYS), today));
            if (np.sessions().isEmpty()) {
                return "새로 볼 세션이 없어요";
            }
            long rescan = np.sessions().stream().filter(PendingSession::rescan).count();
            String label = "새로 볼 세션 " + np.sessions().size() + "개" + (rescan > 0 ? "(이어진 세션 " + rescan + "개는 이어진 부분만)" : "");
            return scan(label, np.data(), calc.activeSessions(np.data()), progress);
        });
    }

    /** 세션 하나(작업 묶음)를 처음부터 다시 본다 — 세션 상세의 「이 세션에서 개선점 찾기」 */
    public JobService.Job submitScan(String taskKey) {
        return jobs.submit("observer:scan:" + taskKey, "세션 개선점 찾기", progress -> {
            progress.update("최신 기록 수집 중");
            collector.collectAndWait();
            LocalDate today = LocalDate.now(props.zone());
            Data head = calc.load(today, today);
            Set<String> ids = new HashSet<>();
            head.taskOf().forEach((sid, tk) -> {
                if (taskKey.equals(tk) && head.sessions().containsKey(sid)) {
                    ids.add(sid);
                }
            });
            if (ids.isEmpty() && head.sessions().containsKey(taskKey)) {
                ids.add(taskKey);
            }
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("세션을 찾지 못했어요(숨긴 세션은 볼 수 없어요)");
            }
            LocalDate from = ids.stream().map(id -> head.sessions().get(id).firstAt()).map(ObserverCalc::parse)
                    .filter(x -> x != null).map(x -> LocalDate.ofInstant(x, zone())).min(Comparator.naturalOrder()).orElse(today);
            Data d = calc.onlySessions(calc.load(from, today), ids);
            String title = head.sessions().get(ids.iterator().next()).displayTitle();
            return scan("세션 「" + title + "」 전체", d, ids, progress);
        });
    }

    private String scan(String label, Data target, Set<String> ids, JobService.Progress progress) throws IOException {
        LocalDate today = LocalDate.now(props.zone());
        Data base = calc.slice(calc.load(today.minusDays(BASELINE_DAYS - 1), today), today.minusDays(BASELINE_DAYS - 1), today, null);
        Observer o = new Observer("scan", today.toString(), label, null, null, null, store.firstDay().orElse(null), metricDefs(),
                calc.snapshot(target), calc.snapshot(base), calc.tools(target), calc.slowCommands(target, 8), calc.errors(target, 10),
                calc.corrections(target, 12), calc.rework(target, 4, 8), calc.sessions(target, 12), calc.models(target), List.of());
        progress.update("Claude 가 세션 " + ids.size() + "개를 보는 중");
        String out = claude.ask(prompt(o));
        List<InsightRequest> list = parseProposals(out, o);
        for (InsightRequest req : list) {
            create(req, "ai", "scan:" + today);
        }
        markScanned(ids, target);
        return list.isEmpty() ? "세션 " + ids.size() + "개를 봤고 새 제안은 없어요" : "세션 " + ids.size() + "개에서 제안 " + list.size() + "개";
    }

    private ZoneId zone() {
        return props.zone() == null ? ZoneId.systemDefault() : props.zone();
    }

    private String prompt(Observer o) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                너는 AI 코딩 에이전트(Claude Code·Codex) 사용 습관 코치다. 아래는 한 개발자의 %s 에이전트 세션 관찰 결과다.
                숫자와 사례를 근거로, 앞으로의 작업을 빠르고 싸고 덜 틀리게 만들 개선안을 1~5개 고른다.

                규칙
                - **개선안 하나 = 규칙 하나.** 서로 다른 행동(문서 먼저 읽기 / 명령 목록 먼저 주기 …)은 따로 나눈다. 사용자가 하나씩 넣고 뺄 수 있게.
                - 각 개선안은 Claude 가 매 세션 읽는 md(CLAUDE.md 또는 자동 메모리)에 넣을 짧은 규칙 문안으로 끝난다:
                  지시문 한국어 마크다운 불릿 1줄(길면 2줄). 구체적 명령·경로·기준을 넣는다. 일반론 금지.
                - tier(단계)를 매긴다 — 사용자가 고민 없이 넣을 것과 골라 넣을 것을 가르는 기준:
                  must(필수) = 실제 사고·권한 거절·같은 정정이 반복됐고, 넣어도 다른 작업에 부작용이 거의 없는 것.
                  recommend(권장) = 효과가 숫자로 보이지만 특정 저장소·상황에만 해당하는 것.
                  optional(선택) = 작업 방식·취향을 바꾸는 것, 상황에 따라 오히려 손해일 수 있는 것.
                  애매하면 한 단계 낮게(must 남발 금지).
                - tierReason = 그 단계인 이유 한 줄(숫자 포함, 30자 안팎). effect = 넣으면 무엇이 얼마나 줄지 한 줄(예: "python 실패 주 23회 중 대부분 제거").
                - 지금 규칙에 이미 있는 내용은 다시 제안하지 않는다. 이미 있는 제안 목록과도 겹치지 않게.
                - metric 은 효과를 확인할 지표 키 하나. 그 지표가 실제로 움직일 개선만 고른다.
                - signals = 재발 횟수를 셀 짧은 문자열 1~4개. 기본은 실패한 도구의 오류 문구·거절된 명령·사용자 정정 요청에서만 센다
                  (예: "No such file", "denied by the auto mode classifier", "그게 아니라"). 명령을 실행한 것 자체가 실수면
                  앞에 cmd: 를 붙인다(성공해도 센다, 예: "cmd:sleep 600", "cmd:push origin HEAD:main").
                  바라는 행동(읽어야 할 문서 이름 등)이나 주제 낱말(폐쇄망, docker load), 흔한 낱말 하나(git, sleep)는 금지.
                - sessions = 이 문제가 실제로 일어난 세션 제목 1~3개. 입력(정정 요청·도구 실패·여러 번 고친 파일·비용 큰 세션)의 [세션 제목]을 글자 그대로.
                - scope 는 아래 '범위' 중 하나 또는 null(전체). target 은 아래 '넣을 곳' 키 중 하나 — 문제가 한 저장소 세션에서만 났으면 그 저장소 memory,
                  여러 저장소에 걸쳤으면 global(그 저장소 세션만 규칙을 읽으므로).
                - 근거가 약하면(표본이 적으면) 개수를 줄인다. 억지로 채우지 않는다.

                출력은 JSON 배열 하나만(설명·코드블록 표시 없이):
                [{"title":"한 줄 제목","tier":"must|recommend|optional","tierReason":"단계 이유 한 줄","effect":"예상 효과 한 줄",
                  "problem":"근거 — 숫자 포함 1~2문장","sessions":["세션 제목"],"signals":["재발 신호"],"metric":"지표 키","scope":"범위 또는 null","target":"넣을 곳 키",
                  "snippet":"- 규칙 문안 1줄"}]

                """.replace("%s", switch (o.kind()) {
            case "scan" -> "새로 생긴(아직 안 본)";
            case "month" -> "이번 달";
            case "year" -> "올해";
            default -> "이번 주";
        }));
        sb.append("scan".equals(o.kind()) ? "## 대상\n" : "## 기간\n").append(o.label()).append(o.scope() == null ? "" : " · 범위 " + o.scope()).append("\n\n");
        String[] words = switch (o.kind()) {
            case "scan" -> new String[] {"이번에 볼 부분", "최근 4주 전체", "-"};
            case "month" -> new String[] {"이번 달", "지난달", "최근 6개월"};
            case "year" -> new String[] {"올해", "작년", "올해 달별"};
            default -> new String[] {"이번 주", "지난주", "최근 8주"};
        };
        sb.append("## 지표 (").append(words[0]).append(" / ").append(words[1])
                .append("scan".equals(o.kind()) ? "" : " / " + words[2] + " 평균").append(")\n");
        for (MetricDef m : o.metricDefs()) {
            Double avg = o.trend().stream().map(t -> t.metrics().get(m.key())).filter(v -> v != null)
                    .mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null);
            if ("scan".equals(o.kind())) {
                sb.append("- ").append(m.key()).append(" (").append(m.label()).append(", ").append(m.unit())
                        .append(m.lowerIsBetter() ? ", 낮을수록 좋음" : ", 참고").append("): ")
                        .append(fmt(o.current().metrics().get(m.key()))).append(" / ").append(fmt(o.previous().metrics().get(m.key())))
                        .append(" — ").append(m.description()).append('\n');
                continue;
            }
            sb.append("- ").append(m.key()).append(" (").append(m.label()).append(", ").append(m.unit())
                    .append(m.lowerIsBetter() ? ", 낮을수록 좋음" : ", 참고").append("): ")
                    .append(fmt(o.current().metrics().get(m.key()))).append(" / ").append(fmt(o.previous().metrics().get(m.key())))
                    .append(" / ").append(fmt(avg)).append(" — ").append(m.description()).append('\n');
        }
        var b = o.current().breakdown();
        var c = o.current().counts();
        sb.append("\n## 시간 분해(분)\n작업 ").append(b.activeSec() / 60).append(", Claude 턴 ").append(b.turnSec() / 60)
                .append(" = 모델 ").append(b.modelSec() / 60).append(" + 도구 실행 ").append(b.toolSec() / 60)
                .append(" + 승인 대기(추정) ").append(b.approvalSec() / 60).append(" + 질문 응답 ").append(b.askSec() / 60)
                .append(", 턴 끝나고 다음 요청까지 ").append(b.nextWaitSec() / 60).append('\n');
        sb.append("세션 ").append(c.sessions()).append(", 요청 ").append(c.prompts()).append(", 도구 호출 ").append(c.toolCalls())
                .append(", 실패 ").append(c.errors()).append(", 거절 ").append(c.rejected()).append(", 중단 ").append(c.interrupts())
                .append(", 압축 ").append(c.compactions()).append(", 비용 $").append(String.format(Locale.ROOT, "%.0f", c.costUsd())).append("\n\n");
        sb.append("## 오래 걸린 명령 (호출 수 · 총 초 · 최대 초 · 실패)\n");
        o.slowCommands().forEach(s -> sb.append("- ").append(s.command()).append(" · ").append(s.calls()).append(" · ")
                .append(s.totalSec()).append(" · ").append(s.maxSec()).append(" · ").append(s.errors()).append(" — 예: ")
                .append(s.sample()).append('\n'));
        sb.append("\n## 도구 실패 (횟수 · 도구 · 대상 · 오류 첫 줄)\n");
        o.errors().forEach(e -> sb.append("- ").append(e.count()).append(" · ").append(e.tool()).append(" · ")
                .append(e.target()).append(" · ").append(e.error()).append('\n'));
        sb.append("\n## 정정·불만 신호가 있는 요청(낱말로 추정 — 오탐 있음)\n");
        o.corrections().forEach(pr -> sb.append("- [").append(pr.sessionTitle()).append("] ").append(pr.text()).append('\n'));
        sb.append("\n## 여러 번 고친 파일 (수정 횟수)\n");
        o.rework().forEach(r -> sb.append("- ").append(r.path()).append(" ×").append(r.edits()).append('\n'));
        sb.append("scan".equals(o.kind()) ? "\n## 이번에 보는 세션" : "\n## 비용 큰 세션")
                .append(" (작업 분 · $ · 압축 · 실패 · 정정 · 가장 긴 턴 분)\n");
        o.sessions().forEach(s -> sb.append("- [").append(s.project()).append("] ").append(s.title()).append(" · ")
                .append(s.activeSec() / 60).append(" · ").append(String.format(Locale.ROOT, "%.0f", s.costUsd())).append(" · ")
                .append(s.compactions()).append(" · ").append(s.errors()).append(" · ").append(s.corrections()).append(" · ")
                .append(s.longestTurnSec() / 60).append('\n'));
        sb.append("\n## 모델별 비용 비중\n");
        o.models().forEach(m -> sb.append("- ").append(m.model()).append(' ').append(String.format(Locale.ROOT, "%.0f%%", m.share())).append('\n'));
        sb.append("\n## 범위(scope)\n").append(scope.labels().isEmpty() ? "(없음 — null)" : String.join(", ", scope.labels())).append("\n");
        sb.append("\n## 넣을 곳 (target 키 — 설명)\n");
        targets.list().forEach(t -> sb.append("- ").append(t.key()).append(" — ").append(t.label()).append('\n'));
        sb.append("\n## 넣을 곳 무게 (매 세션 통째로 읽힘 — 상한이면 그곳엔 새 규칙을 내지 말 것)\n").append(ruleLoad.summaryForPrompt());
        sb.append("\n## 이미 있는 제안(겹치지 말 것)\n");
        insightRows().stream().filter(r -> !"rejected".equals(r.status))
                .forEach(r -> sb.append("- [").append(r.status).append("] ").append(r.title).append('\n'));
        sb.append("\n## 지금 규칙(파일 뒷부분)\n").append(targets.currentRules(3000));
        return sb.toString();
    }

    List<InsightRequest> parseProposals(String out, Observer o) throws IOException {
        int s = out.indexOf('[');
        int e = out.lastIndexOf(']');
        if (s < 0 || e <= s) {
            throw new IOException("제안을 JSON 으로 받지 못함: " + out.substring(0, Math.min(200, out.length())));
        }
        JsonNode arr = MAPPER.readTree(out.substring(s, e + 1));
        Map<String, Target> tmap = new HashMap<>();
        targets.list().forEach(t -> tmap.put(t.key(), t));
        List<InsightRequest> list = new ArrayList<>();
        for (JsonNode n : arr) {
            String title = n.path("title").asText("").strip();
            if (title.isEmpty()) {
                continue;
            }
            String metric = n.path("metric").asText(null);
            String scope = n.path("scope").asText(null);
            String target = n.path("target").asText(null);
            List<String> titles = new ArrayList<>();
            n.path("sessions").forEach(t -> titles.add(t.asText("").strip()));
            List<SourceSession> sessions = sessionsByTitle(titles, o);
            list.add(new InsightRequest(title, n.path("problem").asText(null),
                    Metric.of(metric).isPresent() ? metric : null,
                    scope != null && this.scope.labels().contains(scope) ? scope : null,
                    recommendTarget(tmap.containsKey(target) ? target : "global", sessions, tmap),
                    n.path("snippet").asText(null), null, null, tierOf(n.path("tier").asText(null)),
                    n.path("tierReason").asText(null), n.path("effect").asText(null), sessions, readSignals(n.path("signals"))));
        }
        return list.stream().limit(5).toList();
    }

    // ───────── 발생 세션 ─────────

    /** AI 가 고른 세션 제목 → 이 기간 관찰 데이터(비용 큰 세션·실패·정정·여러 번 고친 파일)의 세션. 못 찾은 제목은 뺀다 */
    private List<SourceSession> sessionsByTitle(List<String> titles, Observer o) {
        Map<String, String> keyOf = new LinkedHashMap<>();
        o.sessions().forEach(x -> keyOf.putIfAbsent(x.title(), x.taskKey()));
        o.errors().forEach(x -> keyOf.putIfAbsent(x.sessionTitle(), x.taskKey()));
        o.corrections().forEach(x -> keyOf.putIfAbsent(x.sessionTitle(), x.taskKey()));
        o.rework().forEach(x -> keyOf.putIfAbsent(x.sessionTitle(), x.taskKey()));
        List<SourceSession> out = new ArrayList<>();
        for (String t : titles) {
            String key = keyOf.get(t);
            if (t.isEmpty() || key == null || out.stream().anyMatch(s -> s.taskKey().equals(key))) {
                continue;
            }
            out.add(new SourceSession(t, key, repoOf(key)));
        }
        return out.stream().limit(3).toList();
    }

    /** 작업 묶음 키(= 그 묶음 첫 세션 id)의 저장소 이름 */
    private String repoOf(String taskKey) {
        return jdbc.sql("SELECT project FROM session WHERE id = ?").param(taskKey).query(String.class).optional().orElse(null);
    }

    /** 발생 세션이 한 저장소뿐이면 그 저장소 메모리, 여러 저장소면 전역(그 저장소 세션만 규칙을 읽으므로). 모르면 AI 가 고른 대로 */
    static String recommendTarget(String chosen, List<SourceSession> sessions, Map<String, Target> tmap) {
        Set<String> repos = new LinkedHashSet<>();
        sessions.forEach(s -> {
            if (s.repo() != null) {
                repos.add(s.repo());
            }
        });
        if (repos.size() == 1 && tmap.containsKey("memory:" + repos.iterator().next())) {
            return "memory:" + repos.iterator().next();
        }
        return repos.size() > 1 ? "global" : chosen;
    }

    /** 발생 세션이 비어 있는 제안에 근거 문장 속 세션 제목으로 세션을 찾아 붙인다(예전 제안용, AI 안 씀). @return 붙인 제안 수 */
    public int linkSessions() {
        TaskCatalog.Index index = catalog.buildForWork();
        List<TaskCatalog.Task> tasks = index.tasks().values().stream()
                .filter(t -> t.title() != null && t.title().strip().length() >= 4)
                .sorted(Comparator.comparingInt((TaskCatalog.Task t) -> t.title().length()).reversed()).toList();
        int n = 0;
        for (InsightRow r : insightRows()) {
            if (!r.sessions.isEmpty() || r.problem == null) {
                continue;
            }
            List<SourceSession> found = new ArrayList<>();
            for (TaskCatalog.Task t : tasks) {
                if (r.problem.contains(t.title().strip()) && found.stream().noneMatch(f -> f.taskKey().equals(t.key()))) {
                    found.add(new SourceSession(t.title().strip(), t.key(), repoOf(t.key())));
                }
                if (found.size() == 3) {
                    break;
                }
            }
            if (!found.isEmpty()) {
                jdbc.sql("UPDATE agent_insight SET sessions = ? WHERE id = ?").params(sessionsJson(found), r.id).update();
                n++;
            }
        }
        return n;
    }

    private static String sessionsJson(List<SourceSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(sessions);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<SourceSession> readSessions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(MAPPER.readValue(json, SourceSession[].class));
        } catch (IOException e) {
            return List.of();
        }
    }

    // ───────── 재발 신호 · 도입 효과 ─────────

    private static final int OUTCOME_DAYS = 28;
    private static final int OUTCOME_WEEKS_BEFORE = 5;

    static String signalsJson(List<String> signals) {
        List<String> clean = signals == null ? List.of()
                : signals.stream().filter(x -> x != null && x.strip().length() >= 3).map(String::strip).distinct().limit(6).toList();
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(clean);
        } catch (IOException e) {
            return null;
        }
    }

    static List<String> readSignals(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return readSignals(MAPPER.readTree(json));
        } catch (IOException e) {
            return List.of();
        }
    }

    static List<String> readSignals(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(x -> {
                String v = x.asText("").strip();
                if (v.length() >= 3 && !out.contains(v)) {
                    out.add(v);
                }
            });
        }
        return out.stream().limit(6).toList();
    }

    /**
     * 도구 호출이 재발인가. cmd: 신호는 명령에 들어 있으면(성공해도), 기본 신호는 실패·거절된 호출의 명령·오류 문구에 들어 있을 때만
     */
    static boolean toolHit(List<String> lowered, String status, String target, String errorText) {
        List<String> cmd = lowered.stream().filter(k -> k.startsWith("cmd:")).map(k -> k.substring(4).strip()).filter(k -> !k.isEmpty()).toList();
        List<String> plain = lowered.stream().filter(k -> !k.startsWith("cmd:")).toList();
        if (hit(cmd, target)) {
            return true;
        }
        return ("error".equals(status) || "rejected".equals(status)) && hit(plain, target, errorText);
    }

    /** 정정 요청 글에 기본 신호가 있나(cmd: 신호는 명령 전용) */
    static boolean promptHit(List<String> lowered, String text) {
        return hit(lowered.stream().filter(k -> !k.startsWith("cmd:")).toList(), text);
    }

    /** 글에 신호가 글자 그대로(대소문자 무시) */
    static boolean hit(List<String> lowered, String... texts) {
        for (String t : texts) {
            if (t == null) {
                continue;
            }
            String l = t.toLowerCase(Locale.ROOT);
            for (String k : lowered) {
                if (l.contains(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 도입한 규칙마다 재발 추이(주별)·도입 전후 10시간당 재발·지표 전후 */
    public Outcomes outcomes() {
        List<InsightRow> rows = insightRows().stream()
                .filter(r -> ("adopted".equals(r.status) || "done".equals(r.status)) && r.adoptedAt != null).toList();
        int missing = (int) rows.stream().filter(r -> r.signals.isEmpty()).count();
        List<InsightRow> withSignals = rows.stream().filter(r -> !r.signals.isEmpty()).toList();
        if (withSignals.isEmpty()) {
            return new Outcomes(List.of(), missing);
        }
        LocalDate today = LocalDate.now(props.zone());
        LocalDate earliest = withSignals.stream().map(r -> day(r.adoptedAt)).min(Comparator.naturalOrder()).orElse(today);
        LocalDate loadFrom = Period.containing("week", earliest.minusWeeks(OUTCOME_WEEKS_BEFORE)).from();
        if (loadFrom.isAfter(earliest.minusDays(OUTCOME_DAYS))) {
            loadFrom = earliest.minusDays(OUTCOME_DAYS);
        }
        Data all = calc.load(loadFrom, today);
        Map<String, InsightInfo> infos = new HashMap<>();
        insights().forEach(i -> infos.put(String.valueOf(i.id()), new InsightInfo(i.after(), i.deltaPct(), i.verdict())));
        List<Outcome> out = new ArrayList<>();
        for (InsightRow r : withSignals) {
            String repo = repoOfTarget(r.target);
            Set<String> ids = repo == null ? null : sessionsOfRepo(repo);
            Data d = ids == null ? all : calc.onlySessions(all, ids);
            List<String> keys = r.signals.stream().map(k -> k.toLowerCase(Locale.ROOT)).toList();
            LocalDate adopted = day(r.adoptedAt);
            Map<String, Integer> hitsByDay = new HashMap<>();
            d.tools().forEach(c -> {
                if (toolHit(keys, c.status(), c.target(), c.errorText())) {
                    hitsByDay.merge(c.day(), 1, Integer::sum);
                }
            });
            d.prompts().forEach(p -> {
                if (ObserverCalc.isCorrection(p.text()) && promptHit(keys, p.text())) {
                    hitsByDay.merge(p.day(), 1, Integer::sum);
                }
            });
            Map<String, Long> secByDay = new HashMap<>();
            d.days().forEach(x -> secByDay.merge(x.day(), x.activeSec(), Long::sum));
            // 주별
            List<OutcomeWeek> weeks = new ArrayList<>();
            for (Period w = Period.containing("week", adopted.minusWeeks(OUTCOME_WEEKS_BEFORE)); !w.from().isAfter(today); w = w.next()) {
                int h = 0;
                long sec = 0;
                for (LocalDate x = w.from(); !x.isAfter(w.to()); x = x.plusDays(1)) {
                    h += hitsByDay.getOrDefault(x.toString(), 0);
                    sec += secByDay.getOrDefault(x.toString(), 0L);
                }
                weeks.add(new OutcomeWeek(w.key(), w.from().toString(), h, sec));
            }
            // 도입 전 28일 / 도입 후(오늘까지, 최대 28일)
            int[] bh = {0};
            long[] bs = {0};
            int[] ah = {0};
            long[] as = {0};
            LocalDate afterEnd = adopted.plusDays(OUTCOME_DAYS - 1).isAfter(today) ? today : adopted.plusDays(OUTCOME_DAYS - 1);
            for (LocalDate x = adopted.minusDays(OUTCOME_DAYS); x.isBefore(adopted); x = x.plusDays(1)) {
                bh[0] += hitsByDay.getOrDefault(x.toString(), 0);
                bs[0] += secByDay.getOrDefault(x.toString(), 0L);
            }
            for (LocalDate x = adopted; !x.isAfter(afterEnd); x = x.plusDays(1)) {
                ah[0] += hitsByDay.getOrDefault(x.toString(), 0);
                as[0] += secByDay.getOrDefault(x.toString(), 0L);
            }
            InsightInfo info = infos.get(String.valueOf(r.id));
            out.add(new Outcome(r.id, r.title, r.status, r.adoptedAt, r.signals, repo, weeks,
                    Period.containing("week", adopted).key(), bh[0], bs[0] / 3600.0, ah[0], as[0] / 3600.0,
                    rate(bh[0], bs[0]), rate(ah[0], as[0]), r.metric, r.baseline, info == null ? null : info.after(),
                    info == null ? null : info.deltaPct(), info == null ? r.verdict : info.verdict(),
                    r.sessions.stream().map(SourceSession::repo).filter(x -> x != null).distinct().toList()));
        }
        return new Outcomes(out, missing);
    }

    private record InsightInfo(Double after, Double deltaPct, String verdict) {
    }

    /** 작업 10시간당 횟수 — 1시간 미만이면 비교 불가 */
    private static Double rate(int hits, long sec) {
        return sec < 3600 ? null : hits / (sec / 36000.0);
    }

    private static String repoOfTarget(String target) {
        if (target == null) {
            return null;
        }
        int c = target.indexOf(':');
        return c > 0 && !target.startsWith("codex") ? target.substring(c + 1) : null;
    }

    private Set<String> sessionsOfRepo(String repo) {
        return new HashSet<>(jdbc.sql("SELECT id FROM session WHERE project = ?").param(repo).query(String.class).list());
    }

    /** 재발 신호가 없는 제안(기각 제외)에 AI 로 신호를 붙인다 */
    public JobService.Job submitSignals() {
        return jobs.submit("observer:signals", "재발 신호 만들기", progress -> {
            List<InsightRow> rows = insightRows().stream().filter(r -> r.signals.isEmpty() && !"rejected".equals(r.status)).toList();
            if (rows.isEmpty()) {
                return "신호가 빠진 제안이 없어요";
            }
            StringBuilder sb = new StringBuilder("""
                    아래 개선 규칙마다, 같은 실수가 다시 났는지 셀 짧은 문자열(재발 신호) 1~4개를 고른다.
                    기본 신호는 실패한 도구의 오류 문구·거절된 명령·사용자 정정 요청 글에서만 센다
                    (예: "No such file", "denied by the auto mode classifier", "그게 아니라").
                    명령을 실행한 것 자체가 실수면 앞에 cmd: 를 붙인다 — 성공해도 센다(예: "cmd:sleep 600", "cmd:push origin HEAD:main").
                    금지: 바라는 행동(읽어야 할 문서 이름 등), 주제 낱말(폐쇄망, docker load), 흔한 낱말 하나(git, sleep, main).
                    출력은 JSON 배열 하나만: [{"id":번호,"signals":["…"]}]

                    """);
            rows.forEach(r -> sb.append("## #").append(r.id).append(' ').append(r.title).append('\n')
                    .append("근거: ").append(r.problem == null ? "" : r.problem).append('\n')
                    .append("규칙: ").append(r.snippet == null ? "" : r.snippet).append("\n\n"));
            progress.update("Claude 가 제안 " + rows.size() + "개의 재발 신호를 고르는 중");
            String out = claude.ask(sb.toString());
            int s0 = out.indexOf('[');
            int e0 = out.lastIndexOf(']');
            if (s0 < 0 || e0 <= s0) {
                throw new IOException("신호를 JSON 으로 받지 못함");
            }
            Set<Long> ok = new HashSet<>();
            rows.forEach(r -> ok.add(r.id));
            int n = 0;
            for (JsonNode x : MAPPER.readTree(out.substring(s0, e0 + 1))) {
                long id = x.path("id").asLong(-1);
                String json = signalsJson(readSignals(x.path("signals")));
                if (ok.contains(id) && json != null) {
                    jdbc.sql("UPDATE agent_insight SET signals = ? WHERE id = ? AND signals IS NULL").params(json, id).update();
                    n++;
                }
            }
            return "제안 " + n + "개에 재발 신호를 붙였어요";
        });
    }

    // ───────── 내부 ─────────

    private record Window(LocalDate from, LocalDate to) {
    }

    private static Window afterWindow(LocalDate adopted, LocalDate today) {
        LocalDate end = adopted.plusDays(WINDOW_DAYS - 1);
        return new Window(adopted, end.isAfter(today) ? today : end);
    }

    static String verdict(String metricKey, Double baseline, Double after) {
        if (baseline == null || after == null) {
            return "측정 불가";
        }
        Metric m = Metric.of(metricKey).orElse(null);
        if (m == null || !m.lowerIsBetter) {
            return "참고 지표";
        }
        if (baseline == 0) {
            return after == 0 ? "변화 없음" : "악화";
        }
        double change = (after - baseline) / Math.abs(baseline);
        if (change <= -SIGNIFICANT) {
            return "개선";
        }
        if (change >= SIGNIFICANT) {
            return "악화";
        }
        return "변화 없음";
    }

    private static Double delta(Double baseline, Double after) {
        if (baseline == null || after == null || baseline == 0) {
            return null;
        }
        return 100 * (after - baseline) / Math.abs(baseline);
    }

    private static final class InsightRow {
        long id;
        String createdAt, weekKey, source, title, problem, metric, scope, target, snippet, status, adoptedAt, appliedPath;
        Double baseline, result;
        String baselineFrom, baselineTo, resultFrom, resultTo, verdict, closedAt, note, tier, tierReason, effect;
        List<SourceSession> sessions = List.of();
        List<String> signals = List.of();
    }

    private List<InsightRow> insightRows() {
        return jdbc.sql("""
                SELECT id, created_at, week_key, source, title, problem, metric, scope, target, snippet, status, adopted_at,
                       applied_path, baseline, baseline_from, baseline_to, result, result_from, result_to, verdict, closed_at, note,
                       tier, tier_reason, effect, sessions, signals
                FROM agent_insight
                ORDER BY CASE status WHEN 'adopted' THEN 0 WHEN 'proposed' THEN 1 WHEN 'done' THEN 2 ELSE 3 END, id DESC
                """).query((rs, i) -> {
            InsightRow r = new InsightRow();
            r.id = rs.getLong(1);
            r.createdAt = rs.getString(2);
            r.weekKey = rs.getString(3);
            r.source = rs.getString(4);
            r.title = rs.getString(5);
            r.problem = rs.getString(6);
            r.metric = rs.getString(7);
            r.scope = rs.getString(8);
            r.target = rs.getString(9);
            r.snippet = rs.getString(10);
            r.status = rs.getString(11);
            r.adoptedAt = rs.getString(12);
            r.appliedPath = rs.getString(13);
            r.baseline = rs.getObject(14) instanceof Number n ? n.doubleValue() : null;
            r.baselineFrom = rs.getString(15);
            r.baselineTo = rs.getString(16);
            r.result = rs.getObject(17) instanceof Number n2 ? n2.doubleValue() : null;
            r.resultFrom = rs.getString(18);
            r.resultTo = rs.getString(19);
            r.verdict = rs.getString(20);
            r.closedAt = rs.getString(21);
            r.note = rs.getString(22);
            r.tier = rs.getString(23);
            r.tierReason = rs.getString(24);
            r.effect = rs.getString(25);
            r.sessions = readSessions(rs.getString(26));
            r.signals = readSignals(rs.getString(27));
            return r;
        }).list();
    }

    private InsightRow row(long id) {
        return insightRows().stream().filter(r -> r.id == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("없는 제안: " + id));
    }

    private void set(long id, String column, String value) {
        if (value == null) {
            return;
        }
        jdbc.sql("UPDATE agent_insight SET " + column + " = ? WHERE id = ?").params(blankToNull(value), id).update();
    }

    private static void validate(InsightRequest req) {
        if (req.metric() != null && !req.metric().isBlank() && Metric.of(req.metric()).isEmpty()) {
            throw new IllegalArgumentException("모르는 지표: " + req.metric());
        }
        if (req.tier() != null && !req.tier().isBlank() && !TIERS.contains(req.tier())) {
            throw new IllegalArgumentException("단계는 must·recommend·optional: " + req.tier());
        }
    }

    static final List<String> TIERS = List.of("must", "recommend", "optional");

    /** 모르는 값·빈 값은 recommend(가운데) */
    private static String tierOf(String tier) {
        return tier != null && TIERS.contains(tier) ? tier : "recommend";
    }

    private static String normalizeScope(String scope) {
        return scope == null || scope.isBlank() || "all".equalsIgnoreCase(scope) || "null".equals(scope) ? null : scope;
    }

    /** PATCH 에서 "all" 은 전체로 되돌리기 — 빈 문자열을 넣어 NULL 로 */
    private static String normalizeScopeForWrite(String scope) {
        String s = normalizeScope(scope);
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private LocalDate day(String isoInstant) {
        return LocalDate.ofInstant(Instant.parse(isoInstant), props.zone() == null ? ZoneId.systemDefault() : props.zone());
    }

    /** 추이 칸들 — 주 보기 8주, 월 보기 6개월(이 달까지), 연 보기 그 해 1~12월 */
    static List<Period> trendPoints(Period p) {
        List<Period> out = new ArrayList<>();
        switch (p.kind()) {
            case "week" -> {
                for (int i = TREND_WEEKS - 1; i >= 0; i--) {
                    out.add(Period.containing("week", p.from().minusWeeks(i)));
                }
            }
            case "month" -> {
                for (int i = TREND_MONTHS - 1; i >= 0; i--) {
                    out.add(Period.containing("month", p.from().minusMonths(i)));
                }
            }
            default -> {
                for (int m = 1; m <= 12; m++) {
                    out.add(Period.containing("month", p.from().withMonth(m)));
                }
            }
        }
        return out;
    }

    private static String shortLabel(Period t) {
        if ("week".equals(t.kind())) {
            LocalDate thu = t.from().plusDays(3);
            return "%d월 %d주".formatted(thu.getMonthValue(), (thu.getDayOfMonth() - 1) / 7 + 1);
        }
        return t.from().getMonthValue() + "월";
    }

    private static String fmt(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }
}
