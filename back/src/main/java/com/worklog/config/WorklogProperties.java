package com.worklog.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("worklog")
public record WorklogProperties(
        Path claudeHome,
        Path dataDir,
        String claudeBin,
        String summaryModel,
        String classifyModel,
        Duration classifyInterval,
        boolean autoClassify,
        ZoneId zone,
        Duration idleGap,
        Duration collectInterval,
        int gitLookbackDays,
        int summaryLookbackDays,
        boolean autoSummary,
        Map<String, ModelPrice> pricing,
        Backup backup,
        Map<String, String> projects,
        List<RoutineSession> routineSessions,
        /** Codex CLI 데이터 폴더(~/.codex) — sessions/ 아래 세션을 같이 모은다 */
        Path codexHome
) {

    /** 반복 업무 세션 — 원래 제목(title)이 같으면 label 로 보이고 계획 외 작업에서 빠진다. color = 캘린더 색 이름 */
    public record RoutineSession(String title, String label, String color) {
    }

    /** mirrorDir 가 비면 맥 안에만 둔다 */
    public record Backup(boolean enabled, Path localDir, Path mirrorDir, int keepDaily, int keepWeekly,
                         int keepMonthly, String keychainService) {
    }

    /** $/1M 토큰 */
    public record ModelPrice(double input, double output, double cacheRead) {
    }

    public Path projectsDir() {
        return claudeHome.resolve("projects");
    }

    public Path liveSessionsDir() {
        return claudeHome.resolve("sessions");
    }

    public Path exportsDir() {
        return dataDir.resolve("exports");
    }
}
