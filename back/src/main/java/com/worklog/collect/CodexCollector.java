package com.worklog.collect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worklog.agent.AgentParser;
import com.worklog.agent.AgentStore;
import com.worklog.config.WorklogProperties;
import com.worklog.usage.UsageParser.UsageRow;
import com.worklog.usage.UsageStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Codex CLI 세션을 Claude Code 세션과 같은 표(session·session_day·prompt·file_edit·usage_event·agent_*)에 옮겨 적는다.
 * 원본 = ~/.codex/sessions/YYYY/MM/DD/rollout-…-{세션 id}.jsonl(Codex 앱에서 보관하면 ~/.codex/archived_sessions 로 옮겨짐),
 * 제목 = ~/.codex/session_index.jsonl 의 마지막 thread_name. Codex 앱의 프로젝트 대화도 같은 파일로 남는다.
 * 세션 표의 source = codex. 위치 기록은 ingest_file 에 "codex:" 를 붙인 경로로.
 * Codex 가 가져온(import) Claude Code 세션은 가져온 시각에 지난 대화가 통째로 다시 찍힌다 — 그 부분은 Claude 쪽에서 이미 모았으니
 * 건너뛰고, 가져온 뒤 Codex 에서 이어서 한 작업만 모은다(external_agent_session_imports.json).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodexCollector {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FILE_ID = Pattern.compile("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.jsonl$");
    private static final int MAX_PROMPT_LENGTH = TranscriptParser.MAX_PROMPT_LENGTH;
    private static final int TITLE_FROM_PROMPT_LENGTH = 60;
    /** 가져온 세션의 재생 줄은 가져온 시각에 찍힌다 — 그 뒤 이만큼까지는 재생으로 본다 */
    private static final long IMPORT_REPLAY_SEC = 120;

    private final WorklogProperties props;
    private final IngestStore store;
    private final UsageStore usage;
    private final AgentStore agent;
    private final GitCli git;
    private final JdbcClient jdbc;

    /** @return 새로 읽은 줄 수 */
    public int collectAll() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String dir : List.of("sessions", "archived_sessions")) {
            Path root = codexHome().resolve(dir);
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.walk(root)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().forEach(files::add);
                }
            }
        }
        if (files.isEmpty()) {
            return 0;
        }
        Map<String, String> titles = titles();
        Map<String, Long> imported = imports();
        int total = 0;
        for (Path f : files) {
            try {
                total += ingest(f, titles, imported);
            } catch (IOException | RuntimeException | StackOverflowError e) {
                log.warn("Codex 세션 수집 실패 {}: {}", f.getFileName(), e.toString());
            }
        }
        return total;
    }

    private Path codexHome() {
        return props.codexHome() != null ? props.codexHome() : Path.of(System.getProperty("user.home"), ".codex");
    }

    /** thread_name 은 이름이 바뀔 때마다 한 줄씩 붙는다 — 마지막 것 */
    private Map<String, String> titles() {
        Map<String, String> m = new HashMap<>();
        Path index = codexHome().resolve("session_index.jsonl");
        if (!Files.exists(index)) {
            return m;
        }
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                try {
                    JsonNode n = MAPPER.readTree(line);
                    if (n.hasNonNull("id") && n.hasNonNull("thread_name")) {
                        m.put(n.path("id").asText(), n.path("thread_name").asText());
                    }
                } catch (IOException e) {
                    // 쓰는 중인 마지막 줄
                }
            }
        } catch (IOException e) {
            log.debug("Codex 제목 목록 읽기 실패: {}", e.toString());
        }
        return m;
    }

    /** 가져온(import) 세션 id → 가져온 시각(epoch 초) */
    private Map<String, Long> imports() {
        Map<String, Long> m = new HashMap<>();
        Path f = codexHome().resolve("external_agent_session_imports.json");
        if (!Files.exists(f)) {
            return m;
        }
        try {
            for (JsonNode r : MAPPER.readTree(f.toFile()).path("records")) {
                if (r.hasNonNull("imported_thread_id") && r.path("imported_at").isNumber()) {
                    m.merge(r.path("imported_thread_id").asText(), r.path("imported_at").asLong(), Math::max);
                }
            }
        } catch (IOException e) {
            log.debug("Codex 가져오기 기록 읽기 실패: {}", e.toString());
        }
        return m;
    }

    private int ingest(Path file, Map<String, String> titles, Map<String, Long> imported) throws IOException {
        String key = "codex:" + file;
        long size = Files.size(file);
        Matcher idm = FILE_ID.matcher(file.getFileName().toString());
        if (!idm.find()) {
            return 0;
        }
        String sid = idm.group(1);
        // 보관하며 옮겨진 파일은 경로가 바뀐다 — 예전 경로에서 읽은 데까지 이어서(처음부터 다시 읽으면 시간이 두 번 셈)
        long offset = store.readOffset(key).orElseGet(() -> movedOffset(sid, size));
        String title = titles.get(sid);
        Long importedAt = imported.get(sid);
        if (size == offset) {
            updateTitle(sid, title);
            return 0;
        }
        if (size < offset) {
            store.resetSession(sid);
            offset = 0;
        }
        SessionAccumulator acc = store.load(sid);
        acc.source = "codex";
        String[] model = {lastModel(sid)};
        List<UsageRow> usageRows = new ArrayList<>();
        List<AgentParser.Item> items = new ArrayList<>();
        int[] ordinal = {0};
        long consumed = JsonlTail.read(file, offset, raw -> {
            ordinal[0]++;
            CodexParser.Line l;
            try {
                l = CodexParser.parse(raw, ordinal[0]);
            } catch (RuntimeException | StackOverflowError e) {
                // 한 줄을 못 읽어도 그 파일·뒤 파일 수집은 계속한다(못 읽은 줄만 빠짐)
                log.warn("Codex 세션 {} {}번째 줄 건너뜀 ({}자): {}", file.getFileName(), ordinal[0], raw.length(), e.toString());
                return;
            }
            if (l == null) {
                return;
            }
            if (l.model() != null) {
                model[0] = l.model();
            }
            if (importedAt != null && l.at().getEpochSecond() <= importedAt + IMPORT_REPLAY_SEC) {
                return; // Claude Code 에서 가져온 지난 대화(Claude 쪽에서 이미 모음)
            }
            if (l.activity()) {
                acc.event(l.at(), props.zone(), props.idleGap());
            }
            LocalDate day = LocalDate.ofInstant(l.at(), props.zone());
            if (l.cwd() != null) {
                acc.cwd = l.cwd();
            }
            if (l.model() != null) {
                model[0] = l.model();
            }
            if (l.userText() != null && l.userUuid() != null && !l.userText().startsWith("<")) {
                String text = l.userText().length() > MAX_PROMPT_LENGTH ? l.userText().substring(0, MAX_PROMPT_LENGTH) + "…" : l.userText();
                acc.prompts.add(new SessionAccumulator.PromptRow(l.userUuid(), l.at(), day, text));
                if (acc.firstPrompt == null) {
                    acc.firstPrompt = firstLine(text);
                }
            }
            if (l.toolStart() != null) {
                items.add(new AgentParser.ToolStart(l.toolStart().callId(), sid, l.at(), l.toolStart().name(), l.toolStart().target(), false));
                List<String> files = l.patchedFiles();
                for (int i = 0; i < files.size(); i++) {
                    String path = files.get(i);
                    Path parent = Path.of(path).getParent();
                    acc.edits.add(new SessionAccumulator.EditRow(l.toolStart().callId() + "#" + i, l.at(), day, "apply_patch", path,
                            parent == null ? null : repoOf(parent).orElse(null)));
                }
            }
            if (l.toolEnd() != null) {
                items.add(new AgentParser.ToolEnd(l.toolEnd().callId(), l.at(), l.toolEnd().status(), l.toolEnd().error()));
            }
            if (l.turnMs() != null) {
                items.add(new AgentParser.Event(sid, l.at(), "turn", l.turnMs(), null));
            }
            if (l.interrupt()) {
                items.add(new AgentParser.Event(sid, l.at(), "interrupt", null, null));
            }
            if (l.compact()) {
                items.add(new AgentParser.Event(sid, l.at(), "compact", null, "auto:0"));
            }
            if (l.tokens() != null && l.tokens().total() > 0) {
                CodexParser.Tokens t = l.tokens();
                // 같은 응답이 두 번 찍히면 누적 합계가 같다 → 같은 id 로 합쳐진다
                usageRows.add(new UsageRow("codex:" + sid + ":" + t.total(), sid, l.at(), model[0] == null ? "codex" : model[0],
                        t.input() + t.cacheWrite(), t.output(), t.cached(), 0, 0, false));
            }
        });
        if (title != null) {
            acc.customTitle = title;
        }
        if (acc.cwd != null) {
            Path cwd = Path.of(acc.cwd);
            acc.repoRoot = git.toplevel(cwd).map(Path::toString).orElse(null);
            acc.project = repoOf(cwd).orElseGet(() ->
                    cwd.equals(Path.of(System.getProperty("user.home"))) ? "~" : String.valueOf(cwd.getFileName()));
        }
        store.flush(List.of(acc), key, consumed);
        usage.save(usageRows, props.zone(), key, consumed);
        agent.save(items, props.zone(), key, consumed);
        return ordinal[0];
    }

    /** 같은 세션 id 를 다른 경로에서 읽은 기록(파일이 그보다 작으면 다른 파일이라 처음부터) */
    private long movedOffset(String sid, long size) {
        return jdbc.sql("SELECT read_offset FROM ingest_file WHERE path LIKE 'codex:%' AND path LIKE ? ORDER BY read_offset DESC LIMIT 1")
                .param("%" + sid + ".jsonl").query(Long.class).optional()
                .filter(o -> o <= size).orElse(0L);
    }

    /** 원본은 그대로인데 Codex 에서 이름만 바꾼 경우 */
    private void updateTitle(String sid, String title) {
        if (title != null) {
            jdbc.sql("UPDATE session SET custom_title = ? WHERE id = ? AND (custom_title IS NULL OR custom_title <> ?)")
                    .params(title, sid, title).update();
        }
    }

    /** 이어 읽을 때 앞부분에 있던 모델을 모르므로 — 마지막으로 기록한 모델 */
    private String lastModel(String sid) {
        return jdbc.sql("SELECT model FROM usage_event WHERE session_id = ? ORDER BY day DESC, message_id DESC LIMIT 1")
                .param(sid).query(String.class).optional().orElse(null);
    }

    private Optional<String> repoOf(Path dir) {
        return git.toplevel(dir).map(root -> {
            String name = String.valueOf(root.getFileName());
            store.upsertRepo(root.toString(), name);
            return name;
        });
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("").strip();
        return line.length() > TITLE_FROM_PROMPT_LENGTH ? line.substring(0, TITLE_FROM_PROMPT_LENGTH) + "…" : line;
    }
}
