package com.worklog.report;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.worklog.collect.CollectScheduler;
import com.worklog.config.Settings.ReportTemplate;
import com.worklog.config.SettingsService;
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
 * 양식(template)은 기본 리포트(null) 또는 설정의 리포트 양식 id. 같은 입력을 그 양식 틀대로 채우고, 양식·기간마다 따로 저장한다
 * (report.kind = 기본은 week·month, 양식은 "report-{id}").
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
    private final SettingsService settings;

    public Optional<ReportRow> get(Period period, String templateId) {
        return store.report(storeKind(period, template(period, templateId)), period.key());
    }

    /** 직접 고친 내용. 비우면 지운다(다시 AI 로 쓰기 전 빈 상태) */
    public void save(Period period, String templateId, String content) {
        String kind = storeKind(period, template(period, templateId));
        if (content == null || content.isBlank()) {
            store.deleteReport(kind, period.key());
            return;
        }
        store.saveReport(kind, period.key(), content.stripTrailing(), "manual");
    }

    /** 양식 이름 — 기본 리포트면 "주간 리포트"·"월간 리포트" */
    public String title(Period period, String templateId) {
        ReportTemplate t = template(period, templateId);
        return t == null ? period.kindLabel() + " 리포트" : t.title();
    }

    /** null·"default" = 기본 리포트. 없는 양식이거나 기간 종류가 다르면 오류 */
    private ReportTemplate template(Period period, String templateId) {
        if (templateId == null || templateId.isBlank() || "default".equals(templateId)) {
            return null;
        }
        ReportTemplate t = settings.reportTemplate(templateId)
                .orElseThrow(() -> new IllegalArgumentException("없는 리포트 양식: " + templateId));
        if (!t.kind().equals(period.kind())) {
            throw new IllegalArgumentException("'" + t.title() + "' 은 " + ("month".equals(t.kind()) ? "월간" : "주간") + " 양식입니다");
        }
        return t;
    }

    private static String storeKind(Period period, ReportTemplate t) {
        return t == null ? period.kind() : "report-" + t.id();
    }

    public JobService.Job submit(Period period, String templateId) {
        ReportTemplate template = template(period, templateId);
        String what = template == null ? period.kindLabel() + " 리포트" : template.title();
        return jobs.submit("report:" + period.kind() + ":" + period.key() + (template == null ? "" : ":" + template.id()),
                what + " " + period.label(), progress -> {
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
                    progress.update(what + " 작성 중");
                    PeriodWork work = workService.periodWork(period.from(), period.to());
                    String input = input(period, work);
                    String prompt = template == null ? prompt(period, input, work.items().stream().anyMatch(ItemView::planned))
                            : templatePrompt(period, template, input);
                    String content = claude.ask(prompt).strip();
                    store.saveReport(storeKind(period, template), period.key(), content, claude.model());
                    return what + " 완료";
                });
    }

    /** 사용자 양식 — 틀은 그대로 두고 빈칸만 입력 근거로 채운다 */
    static String templatePrompt(Period period, ReportTemplate t, String input) {
        StringBuilder rules = new StringBuilder();
        if (!t.categories().isEmpty()) {
            rules.append("- 범위: 카테고리 ").append(String.join(", ", t.categories()))
                    .append(" 에 속한 업무·작업·커밋만 다룬다. 입력의 [카테고리]·[프로젝트]·[저장소] 표기로 판단하고, 다른 카테고리 내용은 쓰지 않는다.\n");
        }
        if (t.guide() != null) {
            rules.append("- 작성 지침(사용자): ").append(t.guide().strip().replace("\n", "\n  ")).append('\n');
        }
        return """
                너는 개발자의 %s 업무 보고서 "%s" 를 쓰는 담당이다. 기간: %s ~ %s (%s).
                아래 입력(통계, 계획 업무와 연결된 작업, 날짜별 하루 요약, git 커밋)만 근거로 쓴다.

                %s
                - 작업 시간은 AI 에이전트 세션 활동 시간 기준 추정치다. 숫자는 입력 통계를 그대로 쓴다.
                %s
                양식 쓰는 법:
                - 아래 양식의 제목·순서·머리표·들여쓰기·고정 문구는 그대로 둔다. <…>·[…]·빈칸·예시 문장 자리만 입력 내용으로 바꿔 채운다.
                - 양식에 반복 항목(예: 업무마다 한 덩어리)이 있으면 입력에 있는 만큼 늘리거나 줄인다.
                - 입력에 근거가 없는 칸은 지어내지 말고 "해당 없음" 으로 둔다.
                - 결과는 채운 양식만. 앞뒤 설명·코드 블록 감싸기 없이.

                ===== 양식 시작 =====
                %s
                ===== 양식 끝 =====

                ---
                %s
                """.formatted(period.kindLabel(), t.title(), period.from(), period.to(), period.label(), SummaryService.RULES,
                rules, t.format(), input);
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
                - 작업 시간은 AI 에이전트 세션 활동 시간 기준 추정치다. 숫자는 입력 통계를 그대로 쓴다.

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
