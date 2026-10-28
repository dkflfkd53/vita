package com.worklog.summary;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worklog.config.WorklogProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * claude -p(헤드리스) 한 번 호출. 도구는 다 끄고 세션도 저장하지 않는다 — 요약 호출이 다시 수집 대상이 되지 않게.
 * 사용자의 Claude 구독으로 돈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeCli {

    private static final long TIMEOUT_MINUTES = 6;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorklogProperties props;

    public String ask(String prompt) throws IOException {
        return ask(prompt, props.summaryModel());
    }

    public String ask(String prompt, String model) throws IOException {
        List<String> command = List.of(resolveBin(), "-p",
                "--model", model,
                "--no-session-persistence",
                "--output-format", "json",
                "--tools", "");
        ProcessBuilder pb = new ProcessBuilder(command).directory(props.dataDir().toFile());
        Map<String, String> env = pb.environment();
        env.put("PATH", env.getOrDefault("PATH", "") + ":/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin");
        Process p = pb.start();
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(p, false));
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readAll(p, true));
        try (OutputStream in = p.getOutputStream()) {
            in.write(prompt.getBytes(StandardCharsets.UTF_8));
        }
        try {
            if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("claude 응답 시간 초과(" + TIMEOUT_MINUTES + "분)");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("중단됨", e);
        }
        String out = new String(stdout.join(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0 && out.isBlank()) {
            throw new IOException("claude 종료 코드 " + p.exitValue() + ": "
                    + new String(stderr.join(), StandardCharsets.UTF_8).strip());
        }
        JsonNode result = MAPPER.readTree(out);
        if (result.path("is_error").asBoolean(false)) {
            throw new IOException("claude 오류: " + result.path("result").asText(out));
        }
        return result.path("result").asText();
    }

    public String model() {
        return props.summaryModel();
    }

    private String resolveBin() throws IOException {
        if (Files.isExecutable(Path.of(props.claudeBin()))) {
            return props.claudeBin();
        }
        // nvm 버전이 바뀌어 경로가 달라졌을 때 — 로그인 셸 PATH 로 다시 찾는다
        Process p = new ProcessBuilder("/bin/zsh", "-lc", "command -v claude").start();
        String found = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (found.isEmpty()) {
            throw new IOException("claude 실행 파일을 찾지 못함 — worklog.claude-bin 을 설정하세요");
        }
        return found;
    }

    private static byte[] readAll(Process p, boolean err) {
        try {
            return (err ? p.getErrorStream() : p.getInputStream()).readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
