package com.worklog.usage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.worklog.collect.JsonlTail;
import com.worklog.config.WorklogProperties;
import com.worklog.usage.UsageParser.UsageRow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 세션 본문(projects/*\/*.jsonl)과 서브에이전트(projects/*\/{세션}/subagents/*.jsonl)에서 토큰 사용량만 읽는다.
 * 위치 기록이 세션 수집과 따로라, 처음 붙일 때는 남아 있는 원본 전체를 읽어 소급한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageCollector {

    private final WorklogProperties props;
    private final UsageStore store;

    /** @return 새로 읽은 응답 줄 수 */
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
            } catch (IOException | RuntimeException e) {
                log.warn("사용량 수집 실패 {}: {}", f.getFileName(), e.toString());
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
            offset = 0; // 다시 쓰인 파일 — message_id 로 합쳐지므로 중복 걱정 없이 처음부터
        }
        // 서브에이전트 파일은 부모 폴더명이 세션 id. 줄에 sessionId 가 없을 때만 쓴다
        boolean sub = "subagents".equals(String.valueOf(file.getParent().getFileName()));
        String fallback = sub ? String.valueOf(file.getParent().getParent().getFileName())
                : file.getFileName().toString().replace(".jsonl", "");
        List<UsageRow> rows = new ArrayList<>();
        long next = JsonlTail.read(file, offset, line -> UsageParser.parse(line, fallback).ifPresent(rows::add));
        store.save(rows, props.zone(), key, next);
        return rows.size();
    }
}
