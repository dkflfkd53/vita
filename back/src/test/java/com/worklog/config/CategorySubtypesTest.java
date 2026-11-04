package com.worklog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteDataSource;

import com.worklog.config.Settings.Category;

/** 카테고리 세부 분류 — 저장·읽기, 빠진 분류를 쓰던 할 일 비우기, 세부 분류를 모르는 요청은 그대로 두기 */
class CategorySubtypesTest {

    @TempDir
    Path dir;

    @Test
    void saveReadAndClearRemoved() {
        SettingsService s = RoutineSessionsTest.settings(dir);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("t.db"));
        JdbcClient jdbc = JdbcClient.create(ds);
                        jdbc.sql("INSERT INTO work_item VALUES (1, '앱', '오류'), (2, '앱', '기능개발'), (3, '기타', NULL)").update();

        s.saveCategories(List.of(new Category("앱", "blue", null, List.of("기능개발", "오류", " 오류 ", "유지보수"), null),
                new Category("기타", null, null, List.of(), null)));
        assertThat(s.subtypes()).containsOnlyKeys("앱");
        assertThat(s.subtypes().get("앱")).containsExactly("기능개발", "오류", "유지보수");

        // 오류를 목록에서 빼면 오류였던 할 일은 미분류로
        s.saveCategories(List.of(new Category("앱", "blue", null, List.of("기능개발", "유지보수"), null), new Category("기타", null, null, null, null)));
        assertThat(jdbc.sql("SELECT subtype FROM work_item WHERE id = 1").query(String.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT subtype FROM work_item WHERE id = 2").query(String.class).single()).isEqualTo("기능개발");

        // subtypes 를 안 보낸 요청(null)은 있던 세부 분류를 지우지 않는다
        s.saveCategories(List.of(new Category("앱", "green", null, null, null), new Category("기타", null, null, null, null)));
        assertThat(s.subtypes().get("앱")).containsExactly("기능개발", "유지보수");
    }

    @Test
    void hiddenCategoryLeavesScopeAndKeepsWhenOmitted() {
        SettingsService s = RoutineSessionsTest.settings(dir);
        s.saveCategories(List.of(new Category("회사", null, null, null, false), new Category("개인", null, null, null, true)));
        assertThat(s.hiddenCategories()).containsExactly("개인");

        s.saveProjects(List.of(new Settings.ProjectRule("/work/acme", "회사"), new Settings.ProjectRule("/home/me/toy", "개인")));
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("t.db"));
        ProjectScope scope = new ProjectScope(s, null, JdbcClient.create(ds));
        assertThat(scope.resolve("/work/acme/api", "api")).contains("회사");
        // 숨긴 카테고리도 관리 대상(업무 보드엔 보임), 세션·리포트 화면과 AI 사용 범위에서만 빠진다
        assertThat(scope.resolve("/home/me/toy/game", "game")).contains("개인");
        assertThat(scope.hidden("개인")).isTrue();
        assertThat(scope.hidden("회사")).isFalse();
        assertThat(scope.labels()).containsExactly("회사");

        // hidden 을 안 보낸 저장은 숨김을 그대로 둔다, false 로 보내면 풀린다
        s.saveCategories(List.of(new Category("회사", "blue", null, null, null), new Category("개인", null, null, null, null)));
        assertThat(s.hiddenCategories()).containsExactly("개인");
        s.saveCategories(List.of(new Category("회사", "blue", null, null, null), new Category("개인", null, null, null, false)));
        assertThat(s.hiddenCategories()).isEmpty();
    }
}
