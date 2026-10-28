package com.worklog.collect;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 수집기 전용 쓰기. 조회 화면용 읽기는 query 패키지가 맡는다 */
@Repository
@RequiredArgsConstructor
public class IngestStore {

    private final JdbcClient jdbc;

    public Optional<Long> readOffset(String path) {
        return jdbc.sql("SELECT read_offset FROM ingest_file WHERE path = ?")
                .param(path).query(Long.class).optional();
    }

    public void saveOffset(String path, long offset) {
        jdbc.sql("""
                INSERT INTO ingest_file (path, read_offset, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET read_offset = excluded.read_offset, updated_at = excluded.updated_at
                """).params(path, offset, Instant.now().toString()).update();
    }

    SessionAccumulator load(String sessionId) {
        SessionAccumulator acc = new SessionAccumulator(sessionId);
        jdbc.sql("""
                SELECT custom_title, ai_title, first_prompt, cwd, git_branch, continued_in, first_at, last_at, last_event_at
                FROM session WHERE id = ?
                """).param(sessionId).query(rs -> {
            acc.customTitle = rs.getString(1);
            acc.aiTitle = rs.getString(2);
            acc.firstPrompt = rs.getString(3);
            acc.cwd = rs.getString(4);
            acc.gitBranch = rs.getString(5);
            acc.continuedIn = rs.getString(6);
            acc.firstAt = instant(rs.getString(7));
            acc.lastAt = instant(rs.getString(8));
            acc.lastEventAt = instant(rs.getString(9));
        });
        return acc;
    }

    /** 원본 파일이 다시 쓰였을 때 — 누적값을 지우고 처음부터 다시 센다. 제목·숨김·이름 변경은 남긴다 */
    @Transactional
    public void resetSession(String sessionId) {
        jdbc.sql("DELETE FROM session_day WHERE session_id = ?").param(sessionId).update();
        jdbc.sql("DELETE FROM prompt WHERE session_id = ?").param(sessionId).update();
        jdbc.sql("DELETE FROM file_edit WHERE session_id = ?").param(sessionId).update();
        jdbc.sql("UPDATE session SET first_at = NULL, last_at = NULL, last_event_at = NULL WHERE id = ?")
                .param(sessionId).update();
    }

    @Transactional
    public void flush(List<SessionAccumulator> sessions, String filePath, long offset) {
        for (SessionAccumulator acc : sessions) {
            flushSession(acc);
        }
        saveOffset(filePath, offset);
    }

    private void flushSession(SessionAccumulator acc) {
        jdbc.sql("""
                INSERT INTO session (id, custom_title, ai_title, first_prompt, cwd, project, git_branch, continued_in,
                                     first_at, last_at, last_event_at, source)
                VALUES (:id, :customTitle, :aiTitle, :firstPrompt, :cwd, :project, :gitBranch, :continuedIn,
                        :firstAt, :lastAt, :lastEventAt, :source)
                ON CONFLICT(id) DO UPDATE SET
                    custom_title = excluded.custom_title, ai_title = excluded.ai_title,
                    first_prompt = excluded.first_prompt, cwd = excluded.cwd,
                    project = COALESCE(excluded.project, session.project), git_branch = excluded.git_branch,
                    continued_in = excluded.continued_in, first_at = excluded.first_at,
                    last_at = excluded.last_at, last_event_at = excluded.last_event_at,
                    source = COALESCE(excluded.source, session.source)
                """)
                .param("id", acc.id)
                .param("customTitle", acc.customTitle)
                .param("aiTitle", acc.aiTitle)
                .param("firstPrompt", acc.firstPrompt)
                .param("cwd", acc.cwd)
                .param("project", acc.project)
                .param("gitBranch", acc.gitBranch)
                .param("continuedIn", acc.continuedIn)
                .param("firstAt", str(acc.firstAt))
                .param("lastAt", str(acc.lastAt))
                .param("lastEventAt", str(acc.lastEventAt))
                .param("source", acc.source)
                .update();

        acc.days.forEach((day, delta) -> jdbc.sql("""
                INSERT INTO session_day (session_id, day, first_at, last_at, active_sec) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(session_id, day) DO UPDATE SET
                    active_sec = session_day.active_sec + excluded.active_sec,
                    first_at = MIN(COALESCE(session_day.first_at, excluded.first_at), excluded.first_at),
                    last_at = MAX(COALESCE(session_day.last_at, excluded.last_at), excluded.last_at)
                """).params(acc.id, day.toString(), str(delta.firstAt), str(delta.lastAt), delta.activeSec).update());

        Set<LocalDate> touched = new HashSet<>(acc.days.keySet());
        for (SessionAccumulator.PromptRow p : acc.prompts) {
            jdbc.sql("INSERT OR IGNORE INTO prompt (uuid, session_id, at, day, text) VALUES (?, ?, ?, ?, ?)")
                    .params(p.uuid(), acc.id, p.at().toString(), p.day().toString(), p.text()).update();
            touched.add(p.day());
        }
        for (SessionAccumulator.EditRow e : acc.edits) {
            jdbc.sql("""
                    INSERT OR IGNORE INTO file_edit (tool_use_id, session_id, at, day, tool, path, repo)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """).params(e.toolUseId(), acc.id, e.at().toString(), e.day().toString(), e.tool(), e.path(), e.repo())
                    .update();
            touched.add(e.day());
        }
        // 같은 줄을 두 번 읽어도(재수집) 개수가 부풀지 않게 원본 표에서 다시 센다
        for (LocalDate day : touched) {
            jdbc.sql("""
                    UPDATE session_day SET
                        prompts = (SELECT COUNT(*) FROM prompt p WHERE p.session_id = session_day.session_id AND p.day = session_day.day),
                        edits = (SELECT COUNT(*) FROM file_edit f WHERE f.session_id = session_day.session_id AND f.day = session_day.day)
                    WHERE session_id = ? AND day = ?
                    """).params(acc.id, day.toString()).update();
        }
    }

    public void upsertRepo(String root, String name) {
        jdbc.sql("INSERT OR IGNORE INTO repo (root, name) VALUES (?, ?)").params(root, name).update();
    }

    public List<String> repoRoots() {
        return jdbc.sql("SELECT root FROM repo ORDER BY root").query(String.class).list();
    }

    public Optional<String> latestCommitAt(String repo) {
        return jdbc.sql("SELECT MAX(at) FROM git_commit WHERE repo = ?").param(repo).query(String.class).optional();
    }

    public int insertCommit(String repo, String hash, Instant at, LocalDate day, String subject) {
        return jdbc.sql("INSERT OR IGNORE INTO git_commit (repo, hash, at, day, subject) VALUES (?, ?, ?, ?, ?)")
                .params(repo, hash, at.toString(), day.toString(), subject).update();
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    private static Instant instant(String s) {
        return s == null ? null : Instant.parse(s);
    }
}
