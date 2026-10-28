package com.worklog.report;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.worklog.collect.CollectScheduler;
import com.worklog.config.WorklogProperties;
import com.worklog.query.QueryStore;
import com.worklog.query.Rows.ReportRow;
import com.worklog.query.Views.CommitView;
import com.worklog.query.Views.DayDetail;
import com.worklog.query.Views.DaySession;
import com.worklog.query.Views.DayTask;
import com.worklog.query.Views.PeriodStats;
import com.worklog.query.Views.ProjectStat;
import com.worklog.query.Views.PromptView;
import com.worklog.query.Views.TaskStat;
import com.worklog.query.WorkQueryService;
import com.worklog.summary.ClaudeCli;
import com.worklog.summary.JobService;
import com.worklog.summary.SummaryService;
import com.worklog.work.ClassifyService;
import com.worklog.work.WorkService;
import com.worklog.work.WorkService.PeriodWork;
import com.worklog.work.WorkViews.ItemView;
import com.worklog.work.WorkViews.LinkedDay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주간·월간 리포트. 기간 안의 하루 요약을 먼저 채운 뒤, 하루 요약 + 통계 + 커밋을 묶어 claude 로 한 번 정리한다.
 * 하루 요약이 아직 없는 오늘은 요청 원문 일부를 대신 넣는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int RAW_PROMPTS_PER_SESSION = 15;

    private final WorklogProperties props;
    private final WorkQueryService query;
    private final QueryStore store;
    private final SummaryService summaries;
    private final ClaudeCli claude;
    private final JobService jobs;
    private final CollectScheduler collector;
    private final WorkService workService;
    private final ClassifyService classify;

    public Optional<ReportRow> get(Period period) {
        return store.report(period.kind(), period.key());
    }

    public JobService.Job submit(Period period) {
        return jobs.submit("report:" + period.kind() + ":" + period.key(),
                period.kindLabel() + " 리포트 " + period.label(), progress -> {
                    collector.collectAndWait();
                    List<LocalDate> missing = summaries.missingDays(period.from(), period.to());
                    for (int i = 0; i < missing.size(); i++) {
                        progress.update("하루 요약 먼저 채우는 중 " + missing.get(i) + " (" + (i + 1) + "/" + missing.size() + ")");
                        try {
                            summaries.summarize(missing.get(i));
                        } catch (Exception e) {
                            log.warn("{} 요약 실패, 원문으로 대신함: {}", missing.get(i), e.getMessage());
                        }
                    }
                    // 업무 기준으로 정리하려면 미분류 작업이 먼저 업무에 붙어 있어야 한다
                    for (LocalDate d = period.from(); props.autoClassify() && !d.isAfter(period.to()); d = d.plusDays(7)) {
                        String week = Period.weekKey(d);
                        progress.update(week + " 업무 분류 확인 중");
                        try {
                            classify.classify(week, false);
                        } catch (Exception e) {
                            log.warn("{} 업무 분류 실패, 분류 없이 진행: {}", week, e.getMessage());
                        }
                    }
                    progress.update("리포트 작성 중");
                    PeriodWork work = workService.periodWork(period.from(), period.to());
                    boolean byWork = work.items().stream().anyMatch(ItemView::planned);
                    String input = input(period, work);
                    String content = claude.ask(prompt(period, input, byWork)).strip();
                    store.saveReport(period.kind(), period.key(), content, claude.model());
                    return period.kindLabel() + " 리포트 완료";
                });
    }

    private String prompt(Period period, String input, boolean byWork) {
        String body = byWork ? """
                ## 업무별 진행 내용
                ### [<카테고리>] <업무명>
                <상태 · 마감일 · 투입 N시간 — 입력의 계획 업무 값을 그대로 한 줄 메타로>
                - 이 기간에 한 일과 결과 2~4개 (입력 "계획 업무"의 연결된 작업 근거. 연결된 작업이 없으면 "이번 기간 진행 기록 없음")
                (계획 업무 순서대로. 계획엔 없었지만 진행된 업무는 업무명 뒤에 "(계획 외)")
                ### 계획 외 작업
                - 어느 업무에도 연결되지 않은 작업을 의미 단위로 묶어 1~3개. 사소하면 생략
                """ : """
                ## 작업별 진행 내용
                ### <작업명>
                <프로젝트 · 작업 N시간 · M일 — 한 줄 메타>
                - 한 일과 결과 2~4개 (작업 시간이 긴 순서. 30분 미만 작업은 맨 끝 "### 기타" 하나로 묶는다)
                """;
        return """
                너는 개발자의 %s 업무 보고서를 쓰는 담당이다. 기간: %s ~ %s (%s).
                아래 입력(통계, 계획 업무와 연결된 작업, 날짜별 하루 요약, git 커밋)만 근거로 쓴다.

                %s
                - 작업 시간은 Claude Code 세션 활동 시간 기준 추정치다. 숫자는 입력 통계를 그대로 쓴다.

                출력 형식(마크다운, 맨 위 # 제목 없이 ## 부터 시작):
                ## 한눈에 보기
                - 이 기간 핵심 성과 3~5개
                %s## 커밋·배포
                - 저장소별로 커밋을 의미 단위로 묶어 요약 (메시지를 그대로 나열하지 않는다)
                ## 이어서 할 일
                - 입력에서 진행 중·미완·대기로 확인되는 것만(마감 임박 업무 우선). 없으면 이 섹션은 뺀다

                ---
                %s
                """.formatted(period.kindLabel(), period.from(), period.to(), period.label(), SummaryService.RULES,
                body, input);
    }

    String input(Period period, PeriodWork work) {
        PeriodStats stats = query.stats(period.from(), period.to());
        StringBuilder sb = new StringBuilder();
        sb.append("## 통계\n")
                .append("- 작업 시간 합계 ").append(hours(stats.activeSec())).append("시간, 일한 날 ")
                .append(stats.activeDays()).append("일, 작업 ").append(stats.taskCount()).append("개, 커밋 ")
                .append(stats.commits()).append("건\n");
        sb.append("- 프로젝트별: ");
        for (ProjectStat p : stats.projects()) {
            sb.append(p.project()).append(' ').append(hours(p.activeSec())).append("시간(커밋 ").append(p.commits()).append(") ");
        }
        sb.append("\n\n## 작업 목록 (작업 시간 순)\n");
        for (TaskStat t : stats.tasks()) {
            sb.append("- ").append(t.title()).append(" [").append(t.project() == null ? "-" : t.project()).append("] ")
                    .append(hours(t.activeSec())).append("시간 · ").append(t.activeDays()).append("일\n");
        }

        appendWork(sb, work);

        sb.append("\n## 날짜별 기록\n");
        LocalDate today = LocalDate.now(props.zone());
        for (LocalDate d = period.from(); !d.isAfter(period.to()) && !d.isAfter(today); d = d.plusDays(1)) {
            DayDetail day = query.day(d);
            if (day.tasks().isEmpty()) {
                continue;
            }
            sb.append("### ").append(d).append(" (").append(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN))
                    .append(")\n");
            if (day.overview() != null) {
                sb.append("하루 요약: ").append(day.overview()).append('\n');
            }
            for (DayTask task : day.tasks()) {
                for (DaySession s : task.sessions()) {
                    sb.append("- [").append(task.title()).append("] ");
                    if (s.summary() != null) {
                        sb.append(s.summary()).append('\n');
                    } else {
                        sb.append("요청 원문: ");
                        List<PromptView> prompts = s.prompts();
                        prompts.stream().skip(Math.max(0, prompts.size() - RAW_PROMPTS_PER_SESSION))
                                .forEach(p -> sb.append(oneLine(p.text())).append(" / "));
                        sb.append('\n');
                    }
                }
            }
        }

        List<CommitView> commits = store.commits(period.from().toString(), period.to().toString()).stream()
                .map(c -> new CommitView(c.repo(), c.hash(), c.at(), c.subject())).toList();
        if (!commits.isEmpty()) {
            sb.append("\n## git 커밋\n");
            commits.forEach(c -> sb.append("- [").append(c.repo()).append("] ").append(c.subject()).append('\n'));
        }
        return sb.toString();
    }

    private static final Map<String, String> STATUS_KO = Map.of(
            "PLANNED", "예정", "IN_PROGRESS", "진행 중", "DONE", "완료", "ON_HOLD", "보류");

    private static void appendWork(StringBuilder sb, PeriodWork work) {
        if (work.items().isEmpty()) {
            return;
        }
        sb.append("\n## 계획 업무\n");
        for (ItemView w : work.items()) {
            sb.append("- [").append(w.category() == null ? "-" : w.category()).append("] ").append(w.title())
                    .append(w.planned() ? "" : " (계획 외)")
                    .append(" | 상태 ").append(STATUS_KO.getOrDefault(w.status(), w.status()))
                    .append(" | 마감 ").append(w.dueDate() == null ? "-" : w.dueDate())
                    .append(" | 투입 ").append(hours(w.activeSec())).append("시간\n");
            if (w.memo() != null) {
                sb.append("  메모: ").append(oneLine(w.memo(), 400)).append('\n');
            }
            for (LinkedDay d : w.sessionDays()) {
                sb.append("  - ").append(d.day()).append(' ').append(d.title()).append(": ")
                        .append(d.summary() == null ? "(요약 없음)" : d.summary()).append('\n');
            }
        }
        if (!work.unassigned().isEmpty()) {
            sb.append("\n## 계획 외 작업 (업무 미연결)\n");
            for (LinkedDay d : work.unassigned()) {
                sb.append("- ").append(d.day()).append(' ').append(d.title()).append(" (")
                        .append(hours(d.activeSec())).append("시간): ")
                        .append(d.summary() == null ? "(요약 없음)" : d.summary()).append('\n');
            }
        }
    }

    static String hours(long sec) {
        return "%.1f".formatted(sec / 3600.0);
    }

    private static String oneLine(String text) {
        return oneLine(text, 150);
    }

    private static String oneLine(String text, int max) {
        String s = text.replaceAll("\\s+", " ").strip();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
