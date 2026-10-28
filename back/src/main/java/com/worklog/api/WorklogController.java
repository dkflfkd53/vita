package com.worklog.api;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.worklog.backup.BackupService;
import com.worklog.collect.CollectScheduler;
import com.worklog.query.QueryStore;
import com.worklog.query.Rows.ReportRow;
import com.worklog.query.Views.UsageBreakdown;
import com.worklog.query.Views.CalendarDay;
import com.worklog.query.Views.DayDetail;
import com.worklog.query.Views.HeatDay;
import com.worklog.query.Views.Overview;
import com.worklog.query.Views.PeriodStats;
import com.worklog.query.Views.SessionInfo;
import com.worklog.query.Views.TaskDetail;
import com.worklog.query.Views.UsageOverview;
import com.worklog.query.WorkQueryService;
import com.worklog.report.ExportService;
import com.worklog.report.Period;
import com.worklog.report.ReportService;
import com.worklog.summary.JobService;
import com.worklog.summary.JobService.JobView;
import com.worklog.summary.SummaryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorklogController {

    private final WorkQueryService query;
    private final QueryStore store;
    private final CollectScheduler collector;
    private final SummaryService summaries;
    private final ReportService reports;
    private final ExportService exports;
    private final JobService jobs;
    private final BackupService backups;

    @GetMapping("/overview")
    public Overview overview() {
        return query.overview();
    }

    /** month=2026-10 또는 from·to(yyyy-MM-dd, 양끝 포함) */
    @GetMapping("/calendar")
    public List<CalendarDay> calendar(@RequestParam(required = false) String month,
                                      @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to) {
        if (from != null && to != null) {
            if (to.isBefore(from) || from.plusDays(120).isBefore(to)) {
                throw new IllegalArgumentException("기간은 120일 이내");
            }
            return query.calendar(from, to);
        }
        if (month == null) {
            throw new IllegalArgumentException("month 또는 from·to 가 필요함");
        }
        return query.calendar(YearMonth.parse(month));
    }

    @GetMapping("/usage")
    public UsageBreakdown usage(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return query.usage(from, to);
    }

    /** AI 사용 화면 위 — 모델·토큰 종류·날짜별 사용량. scope = 카테고리(비우면 전체) */
    @GetMapping("/usage/overview")
    public UsageOverview usageOverview(@RequestParam LocalDate from, @RequestParam LocalDate to,
                                                              @RequestParam(required = false) String scope) {
        return query.usageOverview(from, to, scope);
    }

    @GetMapping("/heatmap")
    public List<HeatDay> heatmap(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return query.heatmap(from, to);
    }

    @GetMapping("/days/{day}")
    public DayDetail day(@PathVariable LocalDate day) {
        return query.day(day);
    }

    @PostMapping("/days/{day}/summary")
    public JobView summarizeDay(@PathVariable LocalDate day) {
        return summaries.submitDay(day).view();
    }

    @GetMapping("/tasks/{key}")
    public ResponseEntity<TaskDetail> task(@PathVariable String key) {
        TaskDetail detail = query.task(key);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    @GetMapping("/sessions/hidden")
    public List<SessionInfo> hiddenSessions() {
        return query.hiddenSessions();
    }

    /** body: {"title": "새 이름" | "" (원래대로), "hidden": true|false} — 둘 중 보낸 것만 바꾼다 */
    @PatchMapping("/sessions/{id}")
    public ResponseEntity<Void> updateSession(@PathVariable String id, @RequestBody SessionPatch patch) {
        int n = store.updateSession(id, patch.title(), patch.hidden());
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    public record SessionPatch(String title, Boolean hidden) {
    }

    @GetMapping("/stats")
    public PeriodStats stats(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return query.stats(from, to);
    }

    /** template = 설정의 리포트 양식 id. 비우면 기본 리포트 */
    @GetMapping("/reports/{kind}/{key}")
    public ReportView report(@PathVariable String kind, @PathVariable String key, @RequestParam(required = false) String template) {
        Period period = Period.of(kind, key);
        ReportRow row = reports.get(period, template).orElse(null);
        return new ReportView(kind, key, blankToDefault(template), reports.title(period, template), period.label(),
                period.from().toString(), period.to().toString(), row == null ? null : row.content(),
                row == null ? null : row.generatedAt(), row != null && "manual".equals(row.model()),
                query.stats(period.from(), period.to()));
    }

    /** edited = 직접 고쳐 저장한 내용 */
    public record ReportView(String kind, String key, String template, String title, String label, String from, String to,
                             String content, String generatedAt, boolean edited, PeriodStats stats) {
    }

    @PostMapping("/reports/{kind}/{key}")
    public JobView generateReport(@PathVariable String kind, @PathVariable String key, @RequestParam(required = false) String template) {
        return reports.submit(Period.of(kind, key), template).view();
    }

    @PutMapping("/reports/{kind}/{key}")
    public ResponseEntity<Void> saveReport(@PathVariable String kind, @PathVariable String key,
                                           @RequestParam(required = false) String template, @RequestBody ReportEdit body) {
        reports.save(Period.of(kind, key), template, body.content());
        return ResponseEntity.noContent().build();
    }

    public record ReportEdit(String content) {
    }

    @PostMapping("/reports/{kind}/{key}/export")
    public Map<String, String> exportReport(@PathVariable String kind, @PathVariable String key,
                                            @RequestParam(required = false) String template) throws IOException {
        return Map.of("path", exports.export(Period.of(kind, key), template).toString());
    }

    private static String blankToDefault(String template) {
        return template == null || template.isBlank() ? "default" : template;
    }

    @PostMapping("/collect")
    public Map<String, Object> collect() {
        boolean ran = collector.collectNow();
        return Map.of("ran", ran, "lastCollectedAt", String.valueOf(collector.lastCollectedAt()));
    }

    @PostMapping("/summaries/catch-up")
    public JobView catchUp() {
        return summaries.submitCatchUp().view();
    }

    @GetMapping("/backups")
    public List<BackupService.BackupFile> backups() throws IOException {
        return backups.list();
    }

    @PostMapping("/backups")
    public BackupService.Result backupNow() throws Exception {
        return backups.backupNow();
    }

    @GetMapping("/jobs")
    public List<JobView> jobs() {
        return jobs.list();
    }
}
