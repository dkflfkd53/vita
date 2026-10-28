package com.worklog.collect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * git 명령 실행과 "이 폴더는 어느 저장소인가" 캐시. 같은 폴더를 수집 때마다 다시 묻지 않는다.
 */
@Slf4j
@Component
public class GitCli {

    private final Map<Path, Optional<Path>> toplevelCache = new ConcurrentHashMap<>();

    /** 폴더(또는 아직 없는 파일 경로의 가장 가까운 기존 상위 폴더)가 속한 저장소 루트 */
    public Optional<Path> toplevel(Path dir) {
        Path existing = dir;
        while (existing != null && !Files.isDirectory(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return Optional.empty();
        }
        return toplevelCache.computeIfAbsent(existing, d ->
                run(d, List.of("rev-parse", "--show-toplevel")).map(String::strip).filter(s -> !s.isEmpty()).map(Path::of));
    }

    public Optional<String> run(Path dir, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(args);
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            p.getErrorStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(new String(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("git 실행 실패: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
