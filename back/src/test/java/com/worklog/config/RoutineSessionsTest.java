package com.worklog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;

import com.worklog.config.Settings.RoutineRule;

class RoutineSessionsTest {

    @TempDir
    Path dir;

    private RoutineSessions routines;

    static SettingsService settings(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("t.db"));
        JdbcClient jdbc = JdbcClient.create(ds);
        jdbc.sql("CREATE TABLE setting (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL)").update();
        jdbc.sql("CREATE TABLE repo (root TEXT PRIMARY KEY, name TEXT NOT NULL)").update();
        WorklogProperties props = new WorklogProperties(dir, dir, "claude", "sonnet", "haiku", null, false, ZoneId.of("Asia/Seoul"),
                Duration.ofMinutes(15), Duration.ofMinutes(10), 0, 0, false, Map.of(), null, Map.of(), null);
        return new SettingsService(jdbc, props);
    }

    @BeforeEach
    void setUp() {
        SettingsService s = settings(dir);
        s.saveRoutines(List.of(new RoutineRule("앱 배포 마스터", "앱 배포", "teal"), new RoutineRule("서버 배포 마스터", "서버 배포", null)));
        routines = new RoutineSessions(s);
    }

    @Test
    void matchesOriginalTitleIgnoringSpacingAndCase() {
        assertThat(routines.labelFor("앱 배포 마스터")).contains("앱 배포");
        assertThat(routines.labelFor(" 서버  배포 마스터 ")).contains("서버 배포");
        assertThat(routines.labelFor("앱 배포 마스터 2")).isEmpty();
    }

    @Test
    void recognizesLabels() {
        assertThat(routines.isRoutine("앱 배포")).isTrue();
        assertThat(routines.isRoutine("앱 배포 마스터")).isFalse();
        assertThat(routines.isRoutine(null)).isFalse();
    }

    @Test
    void colorOnlyForRoutineWithColor() {
        assertThat(routines.colorOf("앱 배포")).isEqualTo("teal");
        assertThat(routines.colorOf("서버 배포")).isNull();
        assertThat(routines.colorOf("다른 작업")).isNull();
    }
}
