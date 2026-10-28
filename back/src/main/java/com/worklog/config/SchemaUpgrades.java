package com.worklog.config;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * schema.sql(CREATE IF NOT EXISTS)로는 이미 있는 표에 컬럼을 못 붙인다. 나중에 생긴 컬럼은 여기서 없을 때만 추가한다.
 * JdbcClient 를 쓰는 빈이라 schema.sql 이 끝난 뒤에 돈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaUpgrades {

    private final JdbcClient jdbc;

    @PostConstruct
    void upgrade() {
        addColumn("work_item", "memo", "TEXT");
        addColumn("category", "color", "TEXT");
        // claude | codex — 비어 있으면 claude(예전 기록)
        addColumn("session", "source", "TEXT");
        // 고정 연결은 건 날부터만 적용(과거 작업 소급 방지). 컬럼 생기기 전 고정은 모두 2026-10-26 에 만든 것
        if (addColumn("session_pin", "pinned_from", "TEXT")) {
            jdbc.sql("UPDATE session_pin SET pinned_from = '2026-10-26' WHERE pinned_from IS NULL").update();
        }
    }

    /** @return 새로 추가했으면 true */
    private boolean addColumn(String table, String column, String type) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?")
                .params(table, column).query(Integer.class).single();
        if (n == 0) {
            jdbc.sql("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type).update();
            log.info("컬럼 추가: {}.{}", table, column);
            return true;
        }
        return false;
    }
}
