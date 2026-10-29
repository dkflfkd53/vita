package com.worklog.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.worklog.collect.JsonlTail;
import com.worklog.config.WorklogProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 도구 호출·턴·압축·중단을 읽는다. 위치 기록이 세션·사용량 수집과 따로라 처음 붙일 때 남아 있는 원본 전체를 소급한다
 * (원본은 Claude Code 가 30일 뒤 지우므로 그보다 이전은 없다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCollector {

    private final WorklogProperties props;
    private final AgentStore store;

    /** @return 새로 읽은 항목 수 */
    public int collectAll() throws IOException {
        Path projects = props.projectsDir();
        if (!Files.isDirectory(projects)) {
            return 0;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(projects)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path p : s.toList()) {
                        if (p.getFileName().toString().endsWith(".jsonl")) {
                            files.add(p);
                        } else if (Files.isDirectory(p.resolve("subagents"))) {
                            try (Stream<Path> sub = Files.list(p.resolve("subagents"))) {
                                sub.filter(f -> f.getFileName().toString().endsWith(".jsonl")).forEach(files::add);
                            }
                        }
                    }
                }
            }
        }
        int total = 0;
        for (Path f : files) {
            try {
                total += ingest(f);
            } catch (IOException | RuntimeException | StackOverflowError e) {
                log.warn("에이전트 기록 수집 실패 {}: {}", f.getFileName(), e.toString());
            }
        }
        return total;
    }

    private int ingest(Path file) throws IOException {
        String key = file.toString();
        long size = Files.size(file);
        long offset = store.offset(key).orElse(0L);
        if (size == offset) {
            return 0;
        }
        if (size < offset) {
            offset = 0; // 다시 쓰인 파일 — 키로 합쳐지므로 처음부터
        }
        boolean sub = "subagents".equals(String.valueOf(file.getParent().getFileName()));
        String fallback = sub ? String.valueOf(file.getParent().getParent().getFileName())
                : file.getFileName().toString().replace(".jsonl", "");
        List<AgentParser.Item> items = new ArrayList<>();
        long next = JsonlTail.read(file, offset, line -> items.addAll(AgentParser.parse(line, fallback)));
        store.save(items, props.zone(), key, next);
        return items.size();
    }
}
