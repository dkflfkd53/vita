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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worklog.agent.ObserverCalc.Data;
import com.worklog.agent.ObserverViews.Insight;
import com.worklog.agent.ObserverViews.InsightRequest;
import com.worklog.agent.ObserverViews.MetricDef;
import com.worklog.agent.ObserverViews.Observer;
import com.worklog.agent.ObserverViews.Snapshot;
import com.worklog.agent.ObserverViews.Target;
import com.worklog.agent.ObserverViews.TrendPoint;
import com.worklog.collect.CollectScheduler;
import com.worklog.config.ProjectScope;
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

    public Observer observer(String weekKey, String scope) {
        Period p = Period.of("week", weekKey);
        String sc = normalizeScope(scope);
        LocalDate trendFrom = p.from().minusWeeks(TREND_WEEKS - 1);
        Data all = calc.load(trendFrom, p.to());
        Data cur = calc.slice(all, p.from(), p.to(), sc);
        Data prev = calc.slice(all, p.from().minusWeeks(1), p.to().minusWeeks(1), sc);

        List<InsightRow> insights = insightRows();
        List<TrendPoint> trend = new ArrayList<>();
        for (int i = TREND_WEEKS - 1; i >= 0; i--) {
            LocalDate mon = p.from().minusWeeks(i);
            String wk = Period.weekKey(mon);
            List<Long> adopted = insights.stream()
                    .filter(r -> r.adoptedAt != null && wk.equals(Period.weekKey(day(r.adoptedAt))))
                    .map(r -> r.id).toList();
            trend.add(new TrendPoint(wk, shortLabel(mon), calc.snapshot(calc.slice(all, mon, mon.plusDays(6), sc)).metrics(), adopted));
        }
        return new Observer(weekKey, p.label(), Period.weekKey(p.from().minusWeeks(1)), Period.weekKey(p.from().plusWeeks(1)),
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
        return rows.stream().map(r -> view(r, data, today, tmap)).toList();
    }

    private Insight view(InsightRow r, Data data, LocalDate today, Map<String, Target> tmap) {
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
                afterFrom, afterTo, days, delta(r.baseline, after), verdict, r.closedAt, r.note);
    }

    public long create(InsightRequest req, String source, String weekKey) {
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("제목을 적으세요");
        }
        validate(req);
        return jdbc.sql("""
                INSERT INTO agent_insight (created_at, week_key, source, title, problem, metric, scope, target, snippet, status, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'proposed', ?) RETURNING id
                """).params(Instant.now().toString(), weekKey == null ? Period.weekKey(LocalDate.now(props.zone())) : weekKey,
                source, req.title().strip(), blankToNull(req.problem()), blankToNull(req.metric()), normalizeScope(req.scope()),
                blankToNull(req.target()), blankToNull(req.snippet()), blankToNull(req.note())).query(Long.class).single();
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

    public JobService.Job submitProposals(String weekKey, String scope) {
        Period p = Period.of("week", weekKey);
        String sc = normalizeScope(scope);
        return jobs.submit("observer:" + weekKey, p.label() + " 에이전트 개선 제안", progress -> {
            progress.update("최신 기록 수집 중");
            collector.collectAndWait();
            Observer o = observer(weekKey, sc);
            progress.update("Claude 가 개선 제안 작성 중");
            String out = claude.ask(prompt(o));
            List<InsightRequest> list = parseProposals(out);
            // 아직 손대지 않은 같은 주 AI 제안은 새 것으로 바꾼다
            jdbc.sql("DELETE FROM agent_insight WHERE week_key = ? AND source = 'ai' AND status = 'proposed'").param(weekKey).update();
            for (InsightRequest req : list) {
                create(req, "ai", weekKey);
            }
            return "개선 제안 " + list.size() + "개";
        });
    }

    private String prompt(Observer o) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                너는 Claude Code 사용 습관 코치다. 아래는 한 개발자의 이번 주 Claude Code 세션 관찰 결과다.
                숫자와 사례를 근거로, 다음 주 작업을 빠르고 싸고 덜 틀리게 만들 개선안을 1~3개 고른다.

                규칙
                - 각 개선안은 Claude 가 매 세션 읽는 md(CLAUDE.md 또는 자동 메모리)에 넣을 **짧은 규칙 문안**으로 끝나야 한다.
                - 문안은 Claude 에게 하는 지시문(한국어, 마크다운 불릿 1~4줄). 구체적 명령·경로·기준을 넣는다. 일반론 금지.
                - 지금 규칙에 이미 있는 내용은 다시 제안하지 않는다. 이미 있는 제안 목록과도 겹치지 않게.
                - metric 은 효과를 확인할 지표 키 하나. 그 지표가 실제로 움직일 개선만 고른다.
                - scope 는 아래 '범위' 중 하나 또는 null(전체). target 은 아래 '넣을 곳' 키 중 하나 — 특정 저장소 일이면 그 저장소 memory, 공통이면 global.
                - 근거가 약하면(표본이 적으면) 개수를 줄인다. 억지로 3개 채우지 않는다.

                출력은 JSON 배열 하나만(설명·코드블록 표시 없이):
                [{"title":"한 줄 제목","problem":"근거 — 숫자 포함 1~2문장","metric":"지표 키","scope":"범위 또는 null","target":"넣을 곳 키","snippet":"- 규칙 문안"}]

                """);
        sb.append("## 기간\n").append(o.label()).append(o.scope() == null ? "" : " · 범위 " + o.scope()).append("\n\n");
        sb.append("## 지표 (이번 주 / 지난주 / 최근 8주 평균)\n");
        for (MetricDef m : o.metricDefs()) {
            Double avg = o.trend().stream().map(t -> t.metrics().get(m.key())).filter(v -> v != null)
                    .mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null);
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
        sb.append("\n## 비용 큰 세션 (작업 분 · $ · 압축 · 실패 · 정정 · 가장 긴 턴 분)\n");
        o.sessions().forEach(s -> sb.append("- [").append(s.project()).append("] ").append(s.title()).append(" · ")
                .append(s.activeSec() / 60).append(" · ").append(String.format(Locale.ROOT, "%.0f", s.costUsd())).append(" · ")
                .append(s.compactions()).append(" · ").append(s.errors()).append(" · ").append(s.corrections()).append(" · ")
                .append(s.longestTurnSec() / 60).append('\n'));
        sb.append("\n## 모델별 비용 비중\n");
        o.models().forEach(m -> sb.append("- ").append(m.model()).append(' ').append(String.format(Locale.ROOT, "%.0f%%", m.share())).append('\n'));
        sb.append("\n## 범위(scope)\n").append(scope.labels().isEmpty() ? "(없음 — null)" : String.join(", ", scope.labels())).append("\n");
        sb.append("\n## 넣을 곳 (target 키 — 설명)\n");
        targets.list().forEach(t -> sb.append("- ").append(t.key()).append(" — ").append(t.label()).append('\n'));
        sb.append("\n## 이미 있는 제안(겹치지 말 것)\n");
        insightRows().stream().filter(r -> !"rejected".equals(r.status))
                .forEach(r -> sb.append("- [").append(r.status).append("] ").append(r.title).append('\n'));
        sb.append("\n## 지금 규칙(파일 뒷부분)\n").append(targets.currentRules(3000));
        return sb.toString();
    }

    List<InsightRequest> parseProposals(String out) throws IOException {
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
            list.add(new InsightRequest(title, n.path("problem").asText(null),
                    Metric.of(metric).isPresent() ? metric : null,
                    scope != null && this.scope.labels().contains(scope) ? scope : null,
                    tmap.containsKey(target) ? target : "global",
                    n.path("snippet").asText(null), null, null));
        }
        return list.stream().limit(3).toList();
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
        String baselineFrom, baselineTo, resultFrom, resultTo, verdict, closedAt, note;
    }

    private List<InsightRow> insightRows() {
        return jdbc.sql("""
                SELECT id, created_at, week_key, source, title, problem, metric, scope, target, snippet, status, adopted_at,
                       applied_path, baseline, baseline_from, baseline_to, result, result_from, result_to, verdict, closed_at, note
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

    private static String shortLabel(LocalDate monday) {
        LocalDate thu = monday.plusDays(3);
        return "%d월 %d주".formatted(thu.getMonthValue(), (thu.getDayOfMonth() - 1) / 7 + 1);
    }

    private static String fmt(Double v) {
        return v == null ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }
}
