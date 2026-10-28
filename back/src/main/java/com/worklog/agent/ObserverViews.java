package com.worklog.agent;

import java.util.List;
import java.util.Map;

/** 에이전트 옵저버 화면 응답 */
public final class ObserverViews {

    private ObserverViews() {
    }

    public record MetricDef(String key, String label, String unit, boolean lowerIsBetter, String description) {
    }

    /** 시간 분해(초). active = 세션 작업 시간(15분 넘는 공백 뺌), 나머지는 턴·도구 기록으로 나눈 값 */
    public record Breakdown(long activeSec, long turnSec, long modelSec, long toolSec, long approvalSec, long askSec,
                            long nextWaitSec) {
    }

    public record Counts(int sessions, int prompts, int turns, int toolCalls, int errors, int rejected, int interrupts,
                         int corrections, int compactions, int edits, double costUsd) {
    }

    public record ToolStat(String tool, int calls, int errors, int rejected, long totalSec, double avgSec, long maxSec) {
    }

    public record CommandStat(String command, int calls, int errors, long totalSec, long maxSec, String sample) {
    }

    public record ErrorSample(String tool, String target, String error, int count, String lastAt, String sessionTitle,
                              String taskKey) {
    }

    public record PromptSample(String text, String at, String sessionTitle, String taskKey) {
    }

    public record ReworkFile(String path, int edits, String sessionTitle, String taskKey) {
    }

    public record SessionStat(String sessionId, String taskKey, String title, String project, long activeSec,
                              double costUsd, int toolCalls, int errors, int corrections, int compactions,
                              long longestTurnSec) {
    }

    public record ModelCost(String model, double costUsd, double share) {
    }

    public record Snapshot(Breakdown breakdown, Counts counts, Map<String, Double> metrics) {
    }

    public record TrendPoint(String week, String label, Map<String, Double> metrics, List<Long> adopted) {
    }

    public record Observer(String week, String label, String prevKey, String nextKey, String scope, String coverageFrom,
                           List<MetricDef> metricDefs, Snapshot current, Snapshot previous,
                           List<ToolStat> tools, List<CommandStat> slowCommands, List<ErrorSample> errors,
                           List<PromptSample> corrections, List<ReworkFile> rework, List<SessionStat> sessions,
                           List<ModelCost> models, List<TrendPoint> trend) {
    }

    public record Target(String key, String label, String path, boolean exists, String kind) {
    }

    /** after* = 도입 뒤 측정(확정 전엔 그때그때 계산), verdict = 개선|변화 없음|악화|측정 중 */
    public record Insight(long id, String createdAt, String weekKey, String source, String title, String problem,
                          String metric, String scope, String target, String targetLabel, String targetPath,
                          String snippet, String status, String adoptedAt, String appliedPath, boolean applied,
                          Double baseline, String baselineFrom, String baselineTo, Double after, String afterFrom,
                          String afterTo, int afterDays, Double deltaPct, String verdict, String closedAt, String note) {
    }

    public record InsightRequest(String title, String problem, String metric, String scope, String target,
                                 String snippet, String note, String status) {
    }
}
