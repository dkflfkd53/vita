package com.worklog.query;

/** 조회용 DB 행. 시각은 ISO-8601(UTC) 문자열, 날짜는 yyyy-MM-dd(로컬) 그대로 화면까지 넘긴다 */
public final class Rows {

    private Rows() {
    }

    public record SessionRow(String id, String customTitle, String aiTitle, String firstPrompt, String titleOverride,
                             String cwd, String project, String gitBranch, String continuedIn,
                             String firstAt, String lastAt, boolean hidden) {

        public String displayTitle() {
            for (String t : new String[]{titleOverride, customTitle, aiTitle, firstPrompt}) {
                if (t != null && !t.isBlank()) {
                    return t.strip();
                }
            }
            return "(제목 없음)";
        }
    }

    public record SessionDayRow(String sessionId, String day, String firstAt, String lastAt,
                                long activeSec, int prompts, int edits, String summary) {
    }

    public record PromptRow(String uuid, String sessionId, String at, String day, String text) {
    }

    public record EditRow(String sessionId, String at, String day, String tool, String path, String repo) {
    }

    public record CommitRow(String repo, String hash, String at, String day, String subject) {
    }

    public record DaySummaryRow(String day, String overview, String model, String generatedAt) {
    }

    public record ReportRow(String kind, String periodKey, String content, String model, String generatedAt) {
    }
}
