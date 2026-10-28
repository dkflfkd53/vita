package com.worklog.collect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.worklog.config.WorklogProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ~/.claude/projects/*\/*.jsonl 을 지난번 읽은 위치부터 이어 읽는다.
 * 줄바꿈으로 끝난 줄까지만 처리하고 위치를 저장하므로, 쓰는 중인 세션도 다음 수집 때 빠짐없이 이어진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscriptCollector {

    private static final int TITLE_FROM_PROMPT_LENGTH = 60;

    private final WorklogProperties props;
    private final IngestStore store;
    private final GitCli git;

    public CollectStats collectAll() throws IOException {
        Path projects = props.projectsDir();
        if (!Files.isDirectory(projects)) {
            log.warn("세션 폴더가 없음: {}", projects);
            return new CollectStats(0, 0);
        }
        List<Path> files;
        try (Stream<Path> dirs = Files.list(projects)) {
            files = dirs.filter(Files::isDirectory)
                    .flatMap(TranscriptCollector::jsonlFiles)
                    .toList();
        }
        int changed = 0;
        int lines = 0;
        for (Path file : files) {
            try {
                int n = ingest(file);
                if (n > 0) {
                    changed++;
                    lines += n;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("세션 파일 수집 실패 {}: {}", file.getFileName(), e.toString());
            }
        }
        return new CollectStats(changed, lines);
    }

    private static Stream<Path> jsonlFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    /** @return 이번에 처리한 줄 수 */
    int ingest(Path file) throws IOException {
        String key = file.toString();
        long size = Files.size(file);
        long offset = store.readOffset(key).orElse(0L);
        if (size == offset) {
            return 0;
        }
        String fileSessionId = file.getFileName().toString().replace(".jsonl", "");
        if (size < offset) {
            log.info("세션 파일이 줄어들어 다시 읽음: {}", file.getFileName());
            store.resetSession(fileSessionId);
            offset = 0;
        }

        Map<String, SessionAccumulator> sessions = new LinkedHashMap<>();
        int[] processed = {0};
        long consumed = JsonlTail.read(file, offset, line -> {
            processed[0]++;
            TranscriptParser.parse(line).ifPresent(p -> apply(p, fileSessionId, sessions));
        });
        if (processed[0] == 0) {
            return 0;
        }
        for (SessionAccumulator acc : sessions.values()) {
            finish(acc);
        }
        store.flush(new ArrayList<>(sessions.values()), key, consumed);
        return processed[0];
    }

    private void apply(ParsedLine p, String fileSessionId, Map<String, SessionAccumulator> sessions) {
        String sid = p.sessionId() != null ? p.sessionId() : fileSessionId;
        SessionAccumulator acc = sessions.computeIfAbsent(sid, store::load);
        switch (p.kind()) {
            case CUSTOM_TITLE -> acc.customTitle = p.value();
            case AI_TITLE -> acc.aiTitle = p.value();
            case CONTINUED_IN -> acc.continuedIn = p.value();
            case EVENT -> {
                acc.event(p.at(), props.zone(), props.idleGap());
                if (p.cwd() != null) {
                    acc.cwd = p.cwd();
                }
                if (p.gitBranch() != null) {
                    acc.gitBranch = p.gitBranch();
                }
                LocalDate day = LocalDate.ofInstant(p.at(), props.zone());
                if (p.promptText() != null && p.promptUuid() != null) {
                    acc.prompts.add(new SessionAccumulator.PromptRow(p.promptUuid(), p.at(), day, p.promptText()));
                    if (acc.firstPrompt == null) {
                        acc.firstPrompt = firstLine(p.promptText());
                    }
                }
                for (ParsedLine.Edit e : p.edits()) {
                    String repo = repoOf(Path.of(e.path()).getParent()).orElse(null);
                    acc.edits.add(new SessionAccumulator.EditRow(e.toolUseId(), p.at(), day, e.tool(), e.path(), repo));
                }
            }
        }
    }

    private void finish(SessionAccumulator acc) {
        if (acc.cwd != null) {
            Path cwd = Path.of(acc.cwd);
            acc.project = repoOf(cwd).orElseGet(() ->
                    cwd.equals(Path.of(System.getProperty("user.home"))) ? "~" : String.valueOf(cwd.getFileName()));
        }
    }

    /** 경로가 속한 git 저장소 이름. 처음 본 저장소는 커밋 수집 대상으로 등록한다 */
    private Optional<String> repoOf(Path dir) {
        if (dir == null) {
            return Optional.empty();
        }
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

    public record CollectStats(int changedFiles, int lines) {
    }
}
