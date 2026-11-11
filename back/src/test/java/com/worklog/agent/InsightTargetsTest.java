package com.worklog.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;

import com.worklog.config.ProjectScope;
import com.worklog.config.Settings.ProjectRule;
import com.worklog.config.SettingsService;
import com.worklog.config.WorklogProperties;

class InsightTargetsTest {

    @TempDir
    Path home;

    private InsightTargets targets() {
        WorklogProperties props = new WorklogProperties(home, home.resolve("data"), "claude", "sonnet", "haiku", null, false,
                ZoneId.of("Asia/Seoul"), Duration.ofMinutes(15), Duration.ofMinutes(10), 0, 0, false, Map.of(), null, Map.of(), null, null);
        return new InsightTargets(props, null, null);
    }

    @Test
    void appendThenRevertKeepsExistingContent() throws Exception {
        Path md = home.resolve("CLAUDE.md");
        Files.writeString(md, "# 내 규칙\n- 기존 줄\n");
        InsightTargets t = targets();

        t.apply(7, "global", "빌드는 한 번에", "근거", "- 빌드 확인은 `./gradlew compileJava -q` 한 번만");
        String after = Files.readString(md);
        assertThat(after).startsWith("# 내 규칙\n- 기존 줄\n\n<!-- vita:insight-7 -->\n- 빌드 확인은");
        assertThat(t.applied(7, "global")).isTrue();

        // 두 번 눌러도 한 번만 들어간다
        t.apply(7, "global", "빌드는 한 번에", "근거", "- 빌드 확인은 `./gradlew compileJava -q` 한 번만");
        assertThat(Files.readString(md)).isEqualTo(after);

        t.revert(7, "global");
        assertThat(Files.readString(md)).isEqualTo("# 내 규칙\n- 기존 줄\n");
        assertThat(t.applied(7, "global")).isFalse();
    }

    @Test
    void createsFileWhenMissing() throws Exception {
        targets().apply(3, "global", "t", null, "- 규칙");
        assertThat(Files.readString(home.resolve("CLAUDE.md")))
                .isEqualTo("<!-- vita:insight-3 -->\n- 규칙\n<!-- /vita:insight-3 -->\n");
    }

    @Test
    void memoryTargetWritesFeedbackFileAndIndexLine() throws Exception {
        Path repo = home.resolve("code/my-app");
        Files.createDirectories(repo);
        Path mem = home.resolve("projects").resolve(repo.toString().replaceAll("[^A-Za-z0-9]", "-")).resolve("memory");
        Files.createDirectories(mem);
        Files.writeString(mem.resolve("MEMORY.md"), "# 메모리\n## Feedback\n- [기존](a.md) — 원래 줄\n");

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + home.resolve("t.db"));
        JdbcClient jdbc = JdbcClient.create(ds);
        jdbc.sql("CREATE TABLE repo (root TEXT PRIMARY KEY, name TEXT NOT NULL)").update();
        jdbc.sql("INSERT INTO repo VALUES (?, 'my-app')").param(repo.toString()).update();
        jdbc.sql("CREATE TABLE session (id TEXT PRIMARY KEY, cwd TEXT)").update();
        WorklogProperties props = new WorklogProperties(home, home.resolve("data"), "claude", "sonnet", "haiku", null, false,
                ZoneId.of("Asia/Seoul"), Duration.ofMinutes(15), Duration.ofMinutes(10), 0, 0, false, Map.of(), null, Map.of(), null, null);
        jdbc.sql("CREATE TABLE setting (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL)").update();
        jdbc.sql("CREATE TABLE category (name TEXT PRIMARY KEY, sort INTEGER, color TEXT, subtypes TEXT, hidden INTEGER NOT NULL DEFAULT 0)").update();
        SettingsService settings = new SettingsService(jdbc, props);
        settings.saveProjects(List.of(new ProjectRule("my-app", "앱")));
        InsightTargets t = new InsightTargets(props, jdbc, new ProjectScope(settings, null, jdbc));

        assertThat(t.list()).extracting(ObserverViews.Target::key).contains("memory:my-app", "repo:my-app");
        Path file = t.apply(5, "memory:my-app", "긴 세션 쪼개기", "압축 2.5배", "- 120분 넘으면 요약 남기고 새 세션");
        assertThat(file.getFileName().toString()).isEqualTo("feedback_vita_insight_5.md");
        assertThat(Files.readString(file)).contains("type: feedback", "- 120분 넘으면 요약 남기고 새 세션", "**Why:** 압축 2.5배");
        assertThat(Files.readString(mem.resolve("MEMORY.md")))
                .endsWith("- [기존](a.md) — 원래 줄\n- [긴 세션 쪼개기](feedback_vita_insight_5.md) — Vita 제안 #5\n");
        assertThat(t.applied(5, "memory:my-app")).isTrue();

        t.revert(5, "memory:my-app");
        assertThat(file).doesNotExist();
        assertThat(Files.readString(mem.resolve("MEMORY.md"))).isEqualTo("# 메모리\n## Feedback\n- [기존](a.md) — 원래 줄\n");
    }

    @Test
    void workspaceFolderMemoryIsATarget() throws Exception {
        // 저장소 여러 개를 담은 작업 폴더에서 연 세션 — 메모리는 저장소 루트가 아니라 작업 폴더 이름으로 쌓인다
        Path work = home.resolve("work");
        Files.createDirectories(work.resolve("my-app"));
        Path mem = home.resolve("projects").resolve(work.toString().replaceAll("[^A-Za-z0-9]", "-")).resolve("memory");
        Files.createDirectories(mem);
        Files.writeString(mem.resolve("MEMORY.md"), "# 메모리\n- [기존](a.md) — 원래 줄\n");

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + home.resolve("w.db"));
        JdbcClient jdbc = JdbcClient.create(ds);
        jdbc.sql("CREATE TABLE repo (root TEXT PRIMARY KEY, name TEXT NOT NULL)").update();
        jdbc.sql("CREATE TABLE session (id TEXT PRIMARY KEY, cwd TEXT)").update();
        jdbc.sql("INSERT INTO session VALUES ('s1', ?)").param(work.toString()).update();
        jdbc.sql("INSERT INTO session VALUES ('s2', ?)").param(home.resolve("elsewhere").toString()).update();
        jdbc.sql("CREATE TABLE setting (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL)").update();
        jdbc.sql("CREATE TABLE category (name TEXT PRIMARY KEY, sort INTEGER, color TEXT, subtypes TEXT, hidden INTEGER NOT NULL DEFAULT 0)").update();
        WorklogProperties props = new WorklogProperties(home, home.resolve("data"), "claude", "sonnet", "haiku", null, false,
                ZoneId.of("Asia/Seoul"), Duration.ofMinutes(15), Duration.ofMinutes(10), 0, 0, false, Map.of(), null, Map.of(), null, null);
        SettingsService settings = new SettingsService(jdbc, props);
        settings.saveProjects(List.of(new ProjectRule(work.toString(), "업무")));
        InsightTargets t = new InsightTargets(props, jdbc, new ProjectScope(settings, null, jdbc));

        List<ObserverViews.Target> memories = t.list().stream().filter(x -> "memory".equals(x.kind())).toList();
        assertThat(memories).hasSize(1);
        String key = memories.getFirst().key();
        assertThat(key).startsWith("memory:").endsWith("work");
        assertThat(t.memoryKeyFor(work.toString())).isEqualTo(key);
        assertThat(t.memoryKeyFor(home.resolve("elsewhere").toString())).isNull();

        Path file = t.apply(9, key, "빌드는 한 번에", "근거", "- 빌드 확인은 한 번만");
        assertThat(file.getParent()).isEqualTo(mem);
        assertThat(Files.readString(mem.resolve("MEMORY.md"))).contains("feedback_vita_insight_9.md");
        t.revert(9, key);
        assertThat(file).doesNotExist();
    }
}
