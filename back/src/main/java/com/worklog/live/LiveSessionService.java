package com.worklog.live;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worklog.config.WorklogProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지금 떠 있는 Claude Code 세션. ~/.claude/sessions/{pid}.json 을 읽고 프로세스가 살아 있는 것만 남긴다.
 * 원본 파일에 쌓인 기록과 달리 저장하지 않는 순간값이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveSessionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorklogProperties props;

    public List<LiveSession> list() {
        Path dir = props.liveSessionsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<LiveSession> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                read(f).ifPresent(result::add);
            }
        } catch (IOException e) {
            log.warn("실행 중 세션 목록 읽기 실패: {}", e.getMessage());
        }
        result.sort(Comparator.comparing(LiveSession::statusUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private Optional<LiveSession> read(Path f) {
        try {
            JsonNode n = MAPPER.readTree(f.toFile());
            long pid = n.path("pid").asLong(-1);
            boolean alive = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive || !n.hasNonNull("sessionId")) {
                return Optional.empty();
            }
            return Optional.of(new LiveSession(
                    pid,
                    n.get("sessionId").asText(),
                    n.path("name").asText(null),
                    "derived".equals(n.path("nameSource").asText()),
                    n.path("cwd").asText(null),
                    n.path("kind").asText(null),
                    n.path("status").asText(null),
                    epoch(n.path("startedAt")),
                    epoch(n.path("statusUpdatedAt"))));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Instant epoch(JsonNode n) {
        return n.isNumber() ? Instant.ofEpochMilli(n.asLong()) : null;
    }

    /** nameDerived = 폴더명으로 자동 붙은 이름(my-app-7d 등)이라 제목으로 쓰기엔 약하다 */
    public record LiveSession(long pid, String sessionId, String name, boolean nameDerived, String cwd,
                              String kind, String status, Instant startedAt, Instant statusUpdatedAt) {
    }
}
