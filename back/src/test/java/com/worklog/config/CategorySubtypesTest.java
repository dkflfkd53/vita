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
        jdbc.sql("CREATE TABLE category (name TEXT PRIMARY KEY, sort INTEGER, color TEXT, subtypes TEXT)").update();
        jdbc.sql("CREATE TABLE work_item (id INTEGER PRIMARY KEY, category TEXT, subtype TEXT)").update();
        jdbc.sql("INSERT INTO work_item VALUES (1, '앱', '오류'), (2, '앱', '기능개발'), (3, '기타', NULL)").update();

        s.saveCategories(List.of(new Category("앱", "blue", null, List.of("기능개발", "오류", " 오류 ", "유지보수")),
                new Category("기타", null, null, List.of())));
        assertThat(s.subtypes()).containsOnlyKeys("앱");
        assertThat(s.subtypes().get("앱")).containsExactly("기능개발", "오류", "유지보수");

        // 오류를 목록에서 빼면 오류였던 할 일은 미분류로
        s.saveCategories(List.of(new Category("앱", "blue", null, List.of("기능개발", "유지보수")), new Category("기타", null, null, null)));
        assertThat(jdbc.sql("SELECT subtype FROM work_item WHERE id = 1").query(String.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT subtype FROM work_item WHERE id = 2").query(String.class).single()).isEqualTo("기능개발");

        // subtypes 를 안 보낸 요청(null)은 있던 세부 분류를 지우지 않는다
        s.saveCategories(List.of(new Category("앱", "green", null, null), new Category("기타", null, null, null)));
        assertThat(s.subtypes().get("앱")).containsExactly("기능개발", "유지보수");
    }
}
