package com.worklog.query;

import java.util.List;

import com.worklog.usage.Pricing;
import com.worklog.usage.UsageService.ModelUsage;
import com.worklog.usage.UsageTotal;

/** API 응답 모양. 프론트 src/api/types.ts 와 1:1 */
public final class Views {

    private Views() {
    }

    /** status: live = 지금 프로세스가 떠 있음, recent = 3일 안에 활동, idle = 그 밖 */
    public record TaskSummary(String key, String title, List<String> projects, int sessionCount,
                              String firstAt, String lastAt, String lastDay, long activeSec, int activeDays,
                              int prompts, int edits, String status, boolean busy, String lastSummary,
                              double costUsd, List<String> tools) {
    }

    /** processes = 같은 작업 묶음을 연 프로세스 수(창·백그라운드 포함) */
    public record LiveView(long pid, String sessionId, String title, String taskKey, String project, String kind,
                           String status, String startedAt, String statusUpdatedAt, int processes) {
    }

    public record Overview(List<LiveView> live, List<TaskSummary> tasks, String lastCollectedAt) {
    }

    /** color = 반복 업무(배포 등)면 캘린더 색 이름, 아니면 null(프로젝트 색) */
    public record CalendarItem(String taskKey, String title, String project, long activeSec, double costUsd, String color) {
    }

    public record CalendarDay(String day, long activeSec, int commits, boolean hasSummary, String overview,
                              List<CalendarItem> items, double costUsd) {
    }

    public record HeatDay(String day, long activeSec, int commits, double costUsd) {
    }

    public record PromptView(String at, String text) {
    }

    public record EditView(String at, String tool, String repo, String path) {
    }

    public record CommitView(String repo, String hash, String at, String subject) {
    }

    public record DaySession(String id, String title, String summary, long activeSec, String firstAt, String lastAt,
                             List<PromptView> prompts, List<EditView> edits, double costUsd) {
    }

    public record DayTask(String taskKey, String title, String project, long activeSec, String firstAt, String lastAt,
                          List<DaySession> sessions, double costUsd, String color) {
    }

    public record DayDetail(String day, String overview, String summaryAt, long activeSec, List<DayTask> tasks,
                            List<CommitView> commits, double costUsd) {
    }

    public record SessionInfo(String id, String title, String originalTitle, boolean renamed, String cwd,
                              String project, String gitBranch, String firstAt, String lastAt, boolean hidden, String tool) {
    }

    public record TaskDay(String day, long activeSec, int prompts, int edits, List<String> summaries,
                          double costUsd) {
    }

    public record TaskDetail(TaskSummary summary, List<SessionInfo> sessions, List<TaskDay> days,
                             List<PromptView> recentPrompts) {
    }

    public record ProjectStat(String project, long activeSec, int commits, double costUsd) {
    }

    public record TaskStat(String taskKey, String title, String project, long activeSec, int activeDays,
                           double costUsd) {
    }

    /** 기간 사용량 — 합계와 모델별(토큰 종류별 양 + 환산 단가) */
    public record UsageBreakdown(String from, String to, UsageTotal total, List<ModelUsage> models) {
    }

    /**
     * AI 사용 화면 위 — 기간 사용량을 모델·토큰 종류·날짜로. previous = 바로 앞 같은 길이 기간의 합계(비교용).
     * scope = 카테고리(비우면 전체). tool = 그 모델을 쓴 도구(claude | codex)
     */
    public record UsageOverview(String from, String to, String scope, UsageTotal total, String previousFrom, String previousTo,
                                UsageTotal previous, List<ModelShare> models, List<UsageDay> days) {
    }

    public record ModelShare(String model, String tool, boolean priced, UsageTotal usage, Pricing.Rate rate,
                             int sessions) {
    }

    /** 하루 모델별 — 금액·토큰 합계만 */
    public record UsageDay(String day, List<ModelDayValue> models) {
    }

    public record ModelDayValue(String model, double costUsd, long tokens) {
    }

    public record PeriodStats(String from, String to, long activeSec, int activeDays, int taskCount, int commits,
                              int prompts, List<ProjectStat> projects, List<TaskStat> tasks, List<HeatDay> days,
                              double costUsd, List<ModelUsage> models) {
    }

    /** 업무 연결 단위 — 세션 × 날짜 하나 */
    public record SessionDayRef(String sessionId, String day, String title, String project, String taskKey,
                                long activeSec, int prompts, int edits, String summary, String firstAt,
                                String lastAt, double costUsd) {

        public String key() {
            return sessionId + "|" + day;
        }
    }
}
