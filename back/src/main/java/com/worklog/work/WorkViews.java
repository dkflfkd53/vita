package com.worklog.work;

import java.util.List;

/** 업무 API 응답. 프론트 src/api/types.ts 와 1:1 */
public final class WorkViews {

    private WorkViews() {
    }

    /** source: manual = 사용자가 지정, pin = 세션 고정, auto = AI, null = 미분류 */
    public record LinkedDay(String key, String sessionId, String day, String title, String project, String taskKey,
                            long activeSec, int prompts, String summary, String source, double costUsd) {
    }

    /** planned = 이 주 계획에 들어 있음. false 면 계획엔 없는데 이 주에 연결된 작업이 있는 업무 */
    public record ItemView(long id, String title, String category, String priority, String status, String dueDate,
                           String createdAt, String updatedAt, String doneAt,
                           String memo, boolean planned, long activeSec, List<LinkedDay> sessionDays, double costUsd,
                           List<ItemSession> sessions, List<String> weeks) {
    }

    /**
     * 할 일에 붙은 세션 하나. pinned = 직접 연결(앞으로의 작업도 이 할 일로), live = 지금 열려 있음.
     * days 는 이 범위(보드면 그 주) 안에서 이 할 일로 잡힌 날짜들
     */
    public record ItemSession(String sessionId, String title, String project, String taskKey, boolean pinned,
                              boolean live, boolean busy, long activeSec, double costUsd, List<LinkedDay> days) {
    }

    /** "세션 추가" 목록 한 줄. linkedItemId = 지금 고정돼 있는 할 일 */
    public record SessionCandidate(String sessionId, String title, String project, String taskKey, boolean live,
                                   boolean busy, String kind, String lastAt, long weekActiveSec, Long linkedItemId,
                                   String linkedItemTitle) {
    }

    /** aiDecided = AI 가 "계획 업무 중 해당 없음"으로 판단, confirmed = 사용자가 계획 외로 확정 */
    public record UnassignedSession(String sessionId, String title, String project, String taskKey, long activeSec,
                                    boolean aiDecided, boolean confirmed, List<LinkedDay> days, double costUsd) {
    }

    public record BoardStats(int itemCount, int doneCount, long activeSec, long linkedSec,
                             long unassignedSec, double costUsd, double linkedCostUsd) {
    }

    /**
     * kind = week | month, key = 2026-W44 | 2026-10. weekKey = 새 할 일·계획 외 등록이 들어갈 주(월 보기면 이번 주 또는 그 달의 끝/첫 주).
     * weeks = 이 기간에 걸친 ISO 주들
     */
    public record Board(String kind, String key, String weekKey, String label, String from, String to, String prevKey,
                        String nextKey, List<String> weeks, List<String> categories, List<ItemView> items,
                        List<UnassignedSession> unassigned, int hiddenShort, List<ItemView> carryover, BoardStats stats) {
    }

    public record ItemDetail(ItemView item, List<String> weeks, int activeDays) {
    }

    public record ItemSummary(long id, String title, String category, String status) {
    }

    /**
     * 세션 묶음(작업) → 할 일. 여러 할 일에 걸쳤으면 가장 최근에 연결된 할 일(itemCount 는 걸친 할 일 수).
     * itemId 가 null 이면 할 일에 안 붙은 작업, routine = 배포 같은 반복 업무 세션
     */
    public record TaskWork(String taskKey, Long itemId, String itemTitle, String itemStatus, String itemCategory,
                           String lastLinkedDay, int itemCount, boolean routine) {
    }

    public record WorkItemRequest(String title, String category, String priority, String status, String dueDate,
                                  String memo, String weekKey) {
    }

    /** days 의 연결을 사용자가 정한다. workItemId null = 계획 외로 확정. pin = 앞으로도 이 세션은 이 업무 */
    public record LinkRequest(String sessionId, List<String> days, Long workItemId, boolean pin) {
    }

    public record FromSessionRequest(String sessionId, String weekKey, List<String> days) {
    }
}
