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

    /** key = 추이 한 칸의 기간 키(주 보기면 주, 월·연 보기면 달) */
    public record TrendPoint(String key, String label, Map<String, Double> metrics, List<Long> adopted) {
    }

    /** kind = week | month | year, key = 2026-W44 | 2026-10 | 2026. previous = 바로 앞 같은 길이 기간(지난주·지난달·작년) */
    public record Observer(String kind, String key, String label, String prevKey, String nextKey, String scope, String coverageFrom,
                           List<MetricDef> metricDefs, Snapshot current, Snapshot previous,
                           List<ToolStat> tools, List<CommandStat> slowCommands, List<ErrorSample> errors,
                           List<PromptSample> corrections, List<ReworkFile> rework, List<SessionStat> sessions,
                           List<ModelCost> models, List<TrendPoint> trend) {
    }

    public record Target(String key, String label, String path, boolean exists, String kind) {
    }

    /**
     * 넣을 곳마다 매 세션 통째로 읽히는 양. file = 실제로 읽히는 파일(메모리는 MEMORY.md 색인).
     * estTokens = 글자로 어림한 토큰(한글 1자≈1, 그 밖 3.5자≈1). vita* = 그중 Vita 가 넣은 규칙.
     * longLines = 150자 넘는 색인 줄(메모리만), budget = 넣을 곳마다 Vita 규칙 권장 상한
     */
    public record RuleLoad(String target, String label, String kind, String file, int chars, int lines, int estTokens,
                           int vitaRules, int vitaChars, int longLines, int budget, int dietPending) {
    }

    /** 색인 줄이기 제안 한 줄 — moved = 줄에서 빠지는 세부 중 링크 문서에 없는 것(문서 끝에 옮겨 적는다) */
    public record DietItem(long id, String target, String file, String oldLine, String newLine, String moved, String reason,
                           String status, int saved, int savedTokens) {
    }

    /** 색인 줄이기를 적용한 기록 — 적용 전후 크기 */
    public record DietLog(String target, String appliedAt, int lines, int beforeChars, int afterChars, int beforeTokens,
                          int afterTokens, String backup) {
    }

    /** after* = 도입 뒤 측정(확정 전엔 그때그때 계산), verdict = 개선|변화 없음|악화|측정 중 */
    public record Insight(long id, String createdAt, String weekKey, String source, String title, String problem,
                          String metric, String scope, String target, String targetLabel, String targetPath,
                          String snippet, String status, String adoptedAt, String appliedPath, boolean applied,
                          Double baseline, String baselineFrom, String baselineTo, Double after, String afterFrom,
                          String afterTo, int afterDays, Double deltaPct, String verdict, String closedAt, String note,
                          String tier, String tierReason, String effect, List<SourceSession> sessions, AddedLoad load,
                          List<String> signals) {
    }

    /** 한 주 — hits = 재발 신호가 나온 횟수, activeSec = 그 주 작업 시간(같은 범위) */
    public record OutcomeWeek(String week, String from, int hits, long activeSec) {
    }

    /**
     * 도입한 규칙의 결과. 재발 = 도입 전 28일 vs 도입 후(오늘까지, 최대 28일)의 작업 10시간당 신호 횟수.
     * repo = 범위(그 저장소 세션만, null 이면 전체). baseline/after/verdict = 고른 지표의 도입 전후(병목 쪽)
     */
    public record Outcome(long id, String title, String status, String adoptedAt, List<String> signals, String repo,
                          List<OutcomeWeek> weeks, String adoptedWeek, int beforeHits, double beforeHours, int afterHits,
                          double afterHours, Double beforeRate, Double afterRate, String metric, Double baseline, Double after,
                          Double deltaPct, String verdict, List<String> sourceRepos, String effect, String target,
                          String targetLabel, int loadTokens) {
    }

    /** missingSignals = 재발 신호가 없어 셀 수 없는 규칙 수(도입·측정 중·결과) */
    public record Outcomes(List<Outcome> rules, int missingSignals) {
    }

    /**
     * 이 규칙을 넣으면 늘어나는 읽기 양(추정 토큰). always = 매 세션 통째로(CLAUDE.md 블록·메모리 색인 한 줄),
     * onDemand = 관련 있을 때만(메모리 본문 파일). targetTokens = 넣을 곳이 지금 매 세션 읽히는 양(도입한 규칙이면 그 규칙 포함)
     */
    public record AddedLoad(int always, int alwaysChars, int onDemand, int targetTokens) {
    }

    /** 제안의 근거가 된 세션 — taskKey 로 세션 상세에 간다, repo = 그 세션의 저장소(넣을 곳 추천에 씀) */
    /** 아직 개선점을 안 본 세션 — rescan = 예전에 봤는데 그 뒤 내용이 이어진 세션. errors·corrections 는 새로 생긴 부분만 */
    public record PendingSession(String sessionId, String taskKey, String title, String project, String lastAt,
                                 int prompts, int errors, int corrections, boolean rescan) {
    }

    public record Pending(List<PendingSession> sessions) {
    }

    public record SourceSession(String title, String taskKey, String repo) {
    }

    /** tier = must(필수: 사고·반복 정정, 부작용 거의 없음) · recommend(권장: 효과는 보이나 상황 한정) · optional(선택: 작업 방식·취향, 부작용 가능) */
    public record InsightRequest(String title, String problem, String metric, String scope, String target,
                                 String snippet, String note, String status, String tier, String tierReason, String effect,
                                 List<SourceSession> sessions, List<String> signals) {
    }
}
