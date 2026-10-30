package com.worklog.query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.worklog.config.ProjectScope;
import com.worklog.config.RoutineSessions;

import com.worklog.query.Rows.CommitRow;
import com.worklog.query.Rows.DaySummaryRow;
import com.worklog.query.Rows.EditRow;
import com.worklog.query.Rows.PromptRow;
import com.worklog.query.Rows.ReportRow;
import com.worklog.query.Rows.SessionDayRow;
import com.worklog.query.Rows.SessionRow;

import lombok.RequiredArgsConstructor;

/** 화면·요약이 쓰는 읽기와, 사용자가 화면에서 바꾸는 값(이름·숨김·요약 결과) 쓰기 */
@Repository
@RequiredArgsConstructor
public class QueryStore {

    private final JdbcClient jdbc;
    private final ProjectScope scope;
    private final RoutineSessions routines;

    public List<SessionRow> sessions() {
        return jdbc.sql("""
                SELECT id, custom_title, ai_title, first_prompt, title_override, cwd, project, git_branch, continued_in,
                       first_at, last_at, hidden, source, repo_root
                FROM session WHERE first_at IS NOT NULL
                """).query((rs, i) -> new Scoped(new SessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11), rs.getInt(12) == 1, rs.getString(13)), rs.getString(14))).list()
                .stream()
                // 관리하는 저장소에서 만든 세션만. project 는 화면 표시 이름(카테고리)으로 바꿔 넘긴다
                .filter(r -> scope.managed(r.row().cwd(), r.row().project(), r.repoRoot()))
                .map(r -> new SessionRow(r.row().id(), r.row().customTitle(), r.row().aiTitle(), r.row().firstPrompt(),
                        r.row().titleOverride(), r.row().cwd(), scope.label(r.row().cwd(), r.row().project(), r.repoRoot()),
                        r.row().gitBranch(), r.row().continuedIn(), r.row().firstAt(), r.row().lastAt(), r.row().hidden(), r.row().source()))
                // 반복 업무 세션(배포 등)은 짧은 이름으로. 사용자가 직접 바꾼 이름이 있으면 그것이 이긴다
                .map(s -> new SessionRow(s.id(), s.customTitle(), s.aiTitle(), s.firstPrompt(),
                        s.titleOverride() != null ? s.titleOverride() : routines.labelFor(originalTitle(s)).orElse(null), s.cwd(),
                        s.project(), s.gitBranch(), s.continuedIn(), s.firstAt(), s.lastAt(), s.hidden(), s.source()))
                .toList();
    }

    /** repoRoot = 작업 폴더의 저장소 루트(worktree 면 원래 저장소) — 범위 판정에만 쓴다 */
    private record Scoped(SessionRow row, String repoRoot) {
    }

    private static String originalTitle(SessionRow s) {
        return new SessionRow(s.id(), s.customTitle(), s.aiTitle(), s.firstPrompt(), null, s.cwd(), s.project(), s.gitBranch(),
                s.continuedIn(), s.firstAt(), s.lastAt(), s.hidden(), s.source()).displayTitle();
    }

    public Optional<SessionRow> session(String id) {
        return sessions().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** from·to 는 yyyy-MM-dd, 양끝 포함 */
    public List<SessionDayRow> sessionDays(String from, String to) {
        return jdbc.sql("""
                SELECT session_id, day, first_at, last_at, active_sec, prompts, edits, summary
                FROM session_day WHERE day BETWEEN ? AND ?
                """).params(from, to).query((rs, i) -> new SessionDayRow(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getLong(5), rs.getInt(6), rs.getInt(7), rs.getString(8))).list();
    }

    public List<SessionDayRow> allSessionDays() {
        return sessionDays("0000-00-00", "9999-99-99");
    }

    public List<PromptRow> prompts(Collection<String> sessionIds, String from, String to) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT uuid, session_id, at, day, text FROM prompt
                WHERE session_id IN (:ids) AND day BETWEEN :from AND :to ORDER BY at
                """).param("ids", sessionIds).param("from", from).param("to", to)
                .query((rs, i) -> new PromptRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5))).list();
    }

    public List<EditRow> edits(Collection<String> sessionIds, String from, String to) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT session_id, at, day, tool, path, repo FROM file_edit
                WHERE session_id IN (:ids) AND day BETWEEN :from AND :to ORDER BY at
                """).param("ids", sessionIds).param("from", from).param("to", to)
                .query((rs, i) -> new EditRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6))).list();
    }

    public List<CommitRow> commits(String from, String to) {
        Map<String, Optional<String>> labels = new HashMap<>();
        return jdbc.sql("SELECT repo, hash, at, day, subject FROM git_commit WHERE day BETWEEN ? AND ? ORDER BY at")
                .params(from, to)
                .query((rs, i) -> new CommitRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5))).list()
                .stream()
                .filter(c -> repoLabel(labels, c.repo()).isPresent())
                .map(c -> new CommitRow(repoLabel(labels, c.repo()).orElseThrow(), c.hash(), c.at(), c.day(), c.subject()))
                .toList();
    }

    /** 커밋의 저장소 → 카테고리(관리 대상이 아니면 빈 값). 저장소마다 한 번만 판정 */
    private Optional<String> repoLabel(Map<String, Optional<String>> cache, String repo) {
        return cache.computeIfAbsent(repo, r -> scope.managedRepo(r) ? Optional.of(scope.repoLabel(r)) : Optional.empty());
    }

    /** 관리 대상 커밋을 저장소 이름 그대로(같은 카테고리 안 저장소를 나눠 볼 때) */
    public List<CommitRow> rawCommits(String from, String to) {
        return jdbc.sql("SELECT repo, hash, at, day, subject FROM git_commit WHERE day BETWEEN ? AND ? ORDER BY at")
                .params(from, to)
                .query((rs, i) -> new CommitRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5))).list()
                .stream().filter(c -> repoLabel(new HashMap<>(), c.repo()).isPresent()).toList();
    }

    /** 저장소별 수정 파일(커밋 전 포함) */
    public List<EditRow> editsInRepo(String repo, String from, String to) {
        return jdbc.sql("""
                SELECT session_id, at, day, tool, path, repo FROM file_edit
                WHERE repo = ? AND day BETWEEN ? AND ? ORDER BY at
                """).params(repo, from, to)
                .query((rs, i) -> new EditRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6))).list();
    }

    /** 사용자가 고쳐 저장한 지난 글들(최근 순) — 문체 예시용 */
    public List<ReportRow> savedReports(String kind, String beforeKey, int limit) {
        return jdbc.sql("""
                SELECT kind, period_key, content, model, generated_at FROM report
                WHERE kind = ? AND period_key < ? AND model = 'manual' ORDER BY period_key DESC LIMIT ?
                """).params(kind, beforeKey, limit)
                .query((rs, i) -> new ReportRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5))).list();
    }

    public void deleteReport(String kind, String periodKey) {
        jdbc.sql("DELETE FROM report WHERE kind = ? AND period_key = ?").params(kind, periodKey).update();
    }

    public List<DaySummaryRow> daySummaries(String from, String to) {
        return jdbc.sql("SELECT day, overview, model, generated_at FROM day_summary WHERE day BETWEEN ? AND ? ORDER BY day")
                .params(from, to)
                .query((rs, i) -> new DaySummaryRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4))).list();
    }

    public void saveDaySummary(String day, String overview, String model) {
        jdbc.sql("""
                INSERT INTO day_summary (day, overview, model, generated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT(day) DO UPDATE SET overview = excluded.overview, model = excluded.model,
                    generated_at = excluded.generated_at
                """).params(day, overview, model, Instant.now().toString()).update();
    }

    public void saveSessionDaySummary(String sessionId, String day, String summary) {
        jdbc.sql("UPDATE session_day SET summary = ? WHERE session_id = ? AND day = ?")
                .params(summary, sessionId, day).update();
    }

    public Optional<ReportRow> report(String kind, String periodKey) {
        return jdbc.sql("SELECT kind, period_key, content, model, generated_at FROM report WHERE kind = ? AND period_key = ?")
                .params(kind, periodKey)
                .query((rs, i) -> new ReportRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5))).optional();
    }

    public void saveReport(String kind, String periodKey, String content, String model) {
        jdbc.sql("""
                INSERT INTO report (kind, period_key, content, model, generated_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(kind, period_key) DO UPDATE SET content = excluded.content, model = excluded.model,
                    generated_at = excluded.generated_at
                """).params(kind, periodKey, content, model, Instant.now().toString()).update();
    }

    /** titleOverride 빈 문자열 = 원래 제목으로 되돌림 */
    public int updateSession(String id, String titleOverride, Boolean hidden) {
        int n = 0;
        if (titleOverride != null) {
            n += jdbc.sql("UPDATE session SET title_override = ? WHERE id = ?")
                    .params(titleOverride.isBlank() ? null : titleOverride.strip(), id).update();
        }
        if (hidden != null) {
            n += jdbc.sql("UPDATE session SET hidden = ? WHERE id = ?").params(hidden ? 1 : 0, id).update();
        }
        return n;
    }
}
