package com.worklog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;

import com.worklog.config.Settings.ProjectRule;

/** 폴더 → 카테고리 — 가장 가까운 경로 규칙, 이름 규칙, 규칙 없을 때 */
class ProjectScopeTest {

    @TempDir
    Path dir;

    private ProjectScope scope(List<ProjectRule> rules) {
        SettingsService s = RoutineSessionsTest.settings(dir);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("t.db"));
        JdbcClient jdbc = JdbcClient.create(ds);
        jdbc.sql("INSERT INTO repo VALUES ('/work/acme/api', 'api'), ('/work/acme/web', 'web'), ('/home/me/toy', 'toy')").update();
        s.saveProjects(rules);
        return new ProjectScope(s, null, jdbc);
    }

    @Test
    void closestPathRuleWins() {
        ProjectScope sc = scope(List.of(new ProjectRule("/work/acme", "회사"), new ProjectRule("/work/acme/web", "프론트")));
        assertThat(sc.resolve("/work/acme/api/src/main", "api")).contains("회사");
        assertThat(sc.resolve("/work/acme/web", "web")).contains("프론트");
        assertThat(sc.resolve("/work/acme/notes", "notes")).contains("회사"); // git 아닌 폴더도 상위 규칙을 따른다
        assertThat(sc.resolve("/work/acmeX", "acmeX")).isEmpty(); // 이름 앞부분만 같은 다른 폴더는 아님
        assertThat(sc.resolve("/home/me/toy", "toy")).isEmpty();
    }

    /** Codex 앱 프로젝트 등 worktree 에서 연 세션 — 작업 폴더는 규칙 밖이어도 원래 저장소 루트로 맞춘다 */
    @Test
    void worktreeFallsBackToRepoRoot() {
        ProjectScope sc = scope(List.of(new ProjectRule("/work/acme", "회사")));
        assertThat(sc.resolve("/home/me/.codex/worktrees/1a2b/api", "api", "/work/acme/api")).contains("회사");
        assertThat(sc.resolve("/home/me/.codex/worktrees/1a2b/api", "api", null)).isEmpty();
        assertThat(sc.resolve("/home/me/.codex/worktrees/1a2b/toy", "toy", "/home/me/toy")).isEmpty();
    }

    @Test
    void nameRuleStillWorksAndPathRulesComeFirst() {
        ProjectScope sc = scope(List.of(new ProjectRule("toy", "개인"), new ProjectRule("/work/acme", "회사"), new ProjectRule("api", "이름규칙")));
        assertThat(sc.resolve("/home/me/toy/x", "toy")).contains("개인");
        assertThat(sc.resolve("/work/acme/api", "api")).contains("회사");
    }

    @Test
    void reposAndCommitsFollowRepoRoots() {
        ProjectScope sc = scope(List.of(new ProjectRule("/work/acme", "회사")));
        assertThat(sc.repos()).containsExactlyInAnyOrder("api", "web");
        assertThat(sc.repoLabel("web")).isEqualTo("회사");
        assertThat(sc.managedRepo("toy")).isFalse();
    }

    @Test
    void noRulesManagesEverythingByRepoName() {
        ProjectScope sc = scope(List.of());
        assertThat(sc.resolve("/anywhere/x", "x")).contains("x");
        assertThat(sc.repos()).containsExactlyInAnyOrder("api", "web", "toy");
    }

    @Test
    void tildeExpandsToHome() {
        assertThat(ProjectScope.expand("~/work")).isEqualTo(Path.of(System.getProperty("user.home"), "work"));
    }
}
