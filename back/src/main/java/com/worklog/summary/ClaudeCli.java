package com.worklog.summary;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worklog.config.SettingsService;
import com.worklog.config.WorklogProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 한 번 호출 — 설정의 엔진에 따라 claude -p 또는 codex exec(헤드리스).
 * 도구는 끄거나(claude) 읽기 전용(codex)으로, 세션도 저장하지 않는다 — 요약 호출이 다시 수집 대상이 되지 않게.
 * 사용자의 Claude / ChatGPT 구독으로 돈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeCli {

    private static final long TIMEOUT_MINUTES = 6;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private final WorklogProperties props;
    private final SettingsService settings;

    public String ask(String prompt) throws IOException {
        return ask(prompt, props.summaryModel());
    }

    /** model = Claude 모델 별칭(sonnet·haiku). Codex 엔진이면 무시하고 설정의 Codex 모델을 쓴다 */
    public String ask(String prompt, String model) throws IOException {
        if (codex()) {
            return askCodex(prompt);
        }
        List<String> command = new ArrayList<>(launcher(resolveBin("claude", props.claudeBin())));
        command.addAll(List.of("-p",
                "--model", model,
                "--no-session-persistence",
                "--output-format", "json",
                "--tools", ""));
        String out = run(command, prompt, "claude");
        JsonNode result = MAPPER.readTree(out);
        if (result.path("is_error").asBoolean(false)) {
            throw new IOException("claude 오류: " + result.path("result").asText(out));
        }
        return result.path("result").asText();
    }

    /** codex exec: 저장 안 하는 세션(--ephemeral), 읽기 전용 샌드박스, 마지막 답만 파일로 받는다 */
    private String askCodex(String prompt) throws IOException {
        Path outFile = Files.createTempFile("vita-codex-", ".txt");
        try {
            List<String> command = new ArrayList<>(launcher(resolveBin("codex", "codex")));
            command.addAll(List.of("exec", "--skip-git-repo-check", "--ephemeral", "-s", "read-only", "--color", "never",
                    "-o", outFile.toString()));
            String m = settings.ai().codexModel();
            if (m != null) {
                command.addAll(List.of("-m", m));
            }
            command.add("-");
            run(command, prompt, "codex");
            String text = Files.readString(outFile, StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                throw new IOException("codex 가 빈 답을 돌려줌");
            }
            return text;
        } finally {
            Files.deleteIfExists(outFile);
        }
    }

    private boolean codex() {
        return "codex".equals(settings.ai().engine());
    }

    /** 프롬프트를 stdin 으로 넣고 stdout 을 받는다 */
    private String run(List<String> command, String prompt, String name) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).directory(props.dataDir().toFile());
        Map<String, String> env = pb.environment();
        if (!WINDOWS) {
            // launchd 로 뜨면 PATH 가 거의 비어 있다
            env.put("PATH", env.getOrDefault("PATH", "") + ":/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin");
        }
        Process p = pb.start();
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(p, false));
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readAll(p, true));
        try (OutputStream in = p.getOutputStream()) {
            in.write(prompt.getBytes(StandardCharsets.UTF_8));
        }
        try {
            if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException(name + " 응답 시간 초과(" + TIMEOUT_MINUTES + "분)");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("중단됨", e);
        }
        String out = new String(stdout.join(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0 && ("codex".equals(name) || out.isBlank())) {
            String err = new String(stderr.join(), StandardCharsets.UTF_8).strip();
            throw new IOException(name + " 종료 코드 " + p.exitValue() + ": " + (err.length() > 500 ? err.substring(err.length() - 500) : err));
        }
        return out;
    }

    /** 저장 기록용 모델 이름 */
    public String model() {
        if (codex()) {
            String m = settings.ai().codexModel();
            return m == null ? "codex" : m;
        }
        return props.summaryModel();
    }

    private String resolveBin(String name, String configured) throws IOException {
        if (Files.isExecutable(Path.of(configured))) {
            return configured;
        }
        // 경로가 바뀌었거나 이름만 적혀 있을 때 — Windows 는 where, 그 밖은 로그인 셸 PATH 로 다시 찾는다
        ProcessBuilder pb = WINDOWS ? new ProcessBuilder("cmd.exe", "/c", "where", name)
                : new ProcessBuilder("/bin/zsh", "-lc", "command -v " + name);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        String found = out.lines().map(String::strip).filter(l -> !l.isEmpty())
                // where 는 확장자 없는 sh 스크립트도 같이 내놓는다 — 실행할 수 있는 .exe/.cmd 를 먼저
                .sorted((a, b) -> Boolean.compare(!isWinExecutable(a), !isWinExecutable(b)))
                .findFirst().orElse("");
        if (found.isEmpty()) {
            throw new IOException(name + " 실행 파일을 찾지 못함 — " + ("codex".equals(name)
                    ? "Codex CLI 를 설치하거나 설정에서 AI 작성을 Claude 로 바꾸세요" : "Claude Code 를 설치하거나 worklog.claude-bin 을 설정하세요"));
        }
        return found;
    }

    private static boolean isWinExecutable(String path) {
        String l = path.toLowerCase(Locale.ROOT);
        return !WINDOWS || l.endsWith(".exe") || l.endsWith(".cmd") || l.endsWith(".bat");
    }

    /** Windows 의 npm 설치판(claude.cmd)은 cmd 로 띄워야 한다 */
    static List<String> launcher(String bin) {
        String l = bin.toLowerCase(Locale.ROOT);
        return WINDOWS && (l.endsWith(".cmd") || l.endsWith(".bat")) ? List.of("cmd.exe", "/c", bin) : List.of(bin);
    }

    private static byte[] readAll(Process p, boolean err) {
        try {
            return (err ? p.getErrorStream() : p.getInputStream()).readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
