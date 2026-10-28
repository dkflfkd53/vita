package com.worklog.work;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class WorkStore {

    private static final String ITEM_COLUMNS =
            "id, title, category, priority, status, due_date, requester, progress, created_at, updated_at, done_at, memo";

    private final JdbcClient jdbc;

    public List<String> categories() {
        return jdbc.sql("SELECT name FROM category ORDER BY sort, name").query(String.class).list();
    }

    public void addCategory(String name) {
        jdbc.sql("INSERT OR IGNORE INTO category (name, sort) VALUES (?, (SELECT COALESCE(MAX(sort), 0) + 1 FROM category WHERE sort < 9))")
                .param(name).update();
    }

    public long createItem(WorkItem draft) {
        String now = Instant.now().toString();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO work_item (title, category, priority, status, due_date, requester, progress,
                                       created_at, updated_at, done_at, memo)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(draft.title(), draft.category(), draft.priority(), draft.status(), draft.dueDate(),
                draft.requester(), draft.progress(), now, now, "DONE".equals(draft.status()) ? now : null, draft.memo())
                .update(keys);
        return keys.getKey().longValue();
    }

    /** 바꿀 컬럼만 넘긴다. 키는 DB 컬럼명이고, 허용 목록 밖은 WorkService 가 걸러서 들어온다 */
    public int updateItem(long id, Map<String, Object> columns) {
        if (columns.isEmpty()) {
            return 0;
        }
        Map<String, Object> values = new LinkedHashMap<>(columns);
        values.put("updated_at", Instant.now().toString());
        StringBuilder sql = new StringBuilder("UPDATE work_item SET ");
        List<Object> params = new ArrayList<>();
        values.forEach((col, v) -> {
            if (!params.isEmpty()) {
                sql.append(", ");
            }
            sql.append(col).append(" = ?");
            params.add(v);
        });
        sql.append(" WHERE id = ?");
        params.add(id);
        return jdbc.sql(sql.toString()).params(params).update();
    }

    @Transactional
    public void deleteItem(long id) {
        jdbc.sql("DELETE FROM week_plan WHERE work_item_id = ?").param(id).update();
        jdbc.sql("DELETE FROM work_link WHERE work_item_id = ?").param(id).update();
        jdbc.sql("DELETE FROM session_pin WHERE work_item_id = ?").param(id).update();
        jdbc.sql("DELETE FROM work_item WHERE id = ?").param(id).update();
    }

    public Optional<WorkItem> item(long id) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM work_item WHERE id = ?").param(id)
                .query(WorkStore::mapItem).optional();
    }

    public List<WorkItem> items(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM work_item WHERE id IN (:ids)").param("ids", ids)
                .query(WorkStore::mapItem).list();
    }

    public List<WorkItem> allItems() {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM work_item ORDER BY id").query(WorkStore::mapItem).list();
    }

    public List<WorkItem> itemsInWeek(String weekKey) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS.replaceAll("(\\w+)", "w.$1") + """
                 FROM work_item w JOIN week_plan p ON p.work_item_id = w.id
                WHERE p.week_key = ? ORDER BY p.added_at, w.id
                """).param(weekKey).query(WorkStore::mapItem).list();
    }

    /** 지난주 계획에 있었는데 끝나지 않았고 이번 주엔 아직 없는 업무 */
    public List<WorkItem> carryoverCandidates(String prevWeek, String week) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS.replaceAll("(\\w+)", "w.$1") + """
                 FROM work_item w JOIN week_plan p ON p.work_item_id = w.id
                WHERE p.week_key = ? AND w.status <> 'DONE'
                  AND NOT EXISTS (SELECT 1 FROM week_plan q WHERE q.week_key = ? AND q.work_item_id = w.id)
                ORDER BY w.id
                """).params(prevWeek, week).query(WorkStore::mapItem).list();
    }

    public void plan(String weekKey, long itemId) {
        jdbc.sql("INSERT OR IGNORE INTO week_plan (week_key, work_item_id, added_at) VALUES (?, ?, ?)")
                .params(weekKey, itemId, Instant.now().toString()).update();
    }

    public void unplan(String weekKey, long itemId) {
        jdbc.sql("DELETE FROM week_plan WHERE week_key = ? AND work_item_id = ?").params(weekKey, itemId).update();
    }

    public List<String> weeksOf(long itemId) {
        return jdbc.sql("SELECT week_key FROM week_plan WHERE work_item_id = ? ORDER BY week_key")
                .param(itemId).query(String.class).list();
    }

    public Map<Long, List<String>> weeksByItem(Collection<Long> ids) {
        Map<Long, List<String>> m = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return m;
        }
        jdbc.sql("SELECT work_item_id, week_key FROM week_plan WHERE work_item_id IN (:ids) ORDER BY week_key")
                .param("ids", ids)
                .query(rs -> {
                    m.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return m;
    }

    public List<Link> links(String from, String to) {
        return jdbc.sql("SELECT session_id, day, work_item_id, source FROM work_link WHERE day BETWEEN ? AND ?")
                .params(from, to).query(WorkStore::mapLink).list();
    }

    public List<Link> allLinks() {
        return links("0000-00-00", "9999-99-99");
    }

    public Map<String, Long> pins() {
        Map<String, Long> pins = new LinkedHashMap<>();
        jdbc.sql("SELECT session_id, work_item_id FROM session_pin")
                .query(rs -> {
                    pins.put(rs.getString(1), rs.getLong(2));
                });
        return pins;
    }

    /** AI 결과는 사용자가 고친 줄(manual)을 덮지 않는다 */
    public void saveAutoLink(String sessionId, String day, Long itemId) {
        jdbc.sql("""
                INSERT INTO work_link (session_id, day, work_item_id, source, linked_at) VALUES (?, ?, ?, 'auto', ?)
                ON CONFLICT(session_id, day) DO UPDATE SET work_item_id = excluded.work_item_id,
                    linked_at = excluded.linked_at
                WHERE work_link.source = 'auto'
                """).params(sessionId, day, itemId, Instant.now().toString()).update();
    }

    public void saveManualLink(String sessionId, String day, Long itemId) {
        jdbc.sql("""
                INSERT INTO work_link (session_id, day, work_item_id, source, linked_at) VALUES (?, ?, ?, 'manual', ?)
                ON CONFLICT(session_id, day) DO UPDATE SET work_item_id = excluded.work_item_id,
                    source = 'manual', linked_at = excluded.linked_at
                """).params(sessionId, day, itemId, Instant.now().toString()).update();
    }

    /** fromDay 부터의 작업만 이 할 일로 본다 */
    public void pin(String sessionId, long itemId, String fromDay) {
        jdbc.sql("""
                INSERT INTO session_pin (session_id, work_item_id, pinned_from) VALUES (?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET work_item_id = excluded.work_item_id, pinned_from = excluded.pinned_from
                """).params(sessionId, itemId, fromDay).update();
    }

    /** 세션 → 고정 시작일 */
    public Map<String, String> pinStarts() {
        Map<String, String> m = new LinkedHashMap<>();
        jdbc.sql("SELECT session_id, pinned_from FROM session_pin").query(rs -> {
            m.put(rs.getString(1), rs.getString(2));
        });
        return m;
    }

    /** 이 세션이 이 업무에 붙은 날짜 연결을 모두 지운다(다시 미분류로) */
    public void unlinkSession(String sessionId, long itemId) {
        jdbc.sql("DELETE FROM work_link WHERE session_id = ? AND work_item_id = ?").params(sessionId, itemId).update();
        jdbc.sql("DELETE FROM session_pin WHERE session_id = ? AND work_item_id = ?").params(sessionId, itemId).update();
    }

    public void unpin(String sessionId) {
        jdbc.sql("DELETE FROM session_pin WHERE session_id = ?").param(sessionId).update();
    }

    private static WorkItem mapItem(ResultSet rs, int i) throws SQLException {
        return new WorkItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getInt(8), rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getString(12));
    }

    private static Link mapLink(ResultSet rs, int i) throws SQLException {
        // getLong 은 NULL 을 0 으로 읽는다 — 0번 업무로 오인하지 않게 객체로 먼저 본다
        Long id = rs.getObject(3) == null ? null : rs.getLong(3);
        return new Link(rs.getString(1), rs.getString(2), id, rs.getString(4));
    }

    public record WorkItem(long id, String title, String category, String priority, String status, String dueDate,
                           String requester, int progress, String createdAt, String updatedAt, String doneAt,
                           String memo) {
    }

    public record Link(String sessionId, String day, Long workItemId, String source) {

        public String key() {
            return sessionId + "|" + day;
        }
    }
}
