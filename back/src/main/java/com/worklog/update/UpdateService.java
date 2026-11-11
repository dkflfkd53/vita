package com.worklog.update;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.worklog.config.WorklogProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 버전 업데이트 — 설치한 저장소(--worklog.repo-dir, 설치 스크립트가 넘긴다)에서 git 으로 새 커밋을 확인하고,
 * 누르면 git pull 뒤 설치 스크립트를 다시 돌려 재시작한다.
 * <p>
 * 설치 스크립트는 지금 서버를 내렸다 다시 띄우므로, 서버와 같은 프로세스 묶음에 있으면 같이 죽는다.
 * 그래서 맥은 setsid 로 새 세션에서, 윈도우는 별도 PowerShell 로 띄운다. 출력은 logs/update.log.
 */
@Slf4j
@Service
public class UpdateService {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    /** 빌드할 때마다 바뀌는 파일 — pull 을 막지 않게 되돌린다 */
    private static final String GENERATED = "front/tsconfig.tsbuildinfo";

    private final Path repo;
    private final Path logs;
    private volatile Status cached;
    private volatile boolean updating;

    public UpdateService(@Value("${worklog.repo-dir:}") String repoDir, WorklogProperties props) {
        this.repo = repoDir == null || repoDir.isBlank() ? null : Path.of(repoDir);
        this.logs = props.dataDir().resolve("logs");
    }

    public record Commit(String sha, String subject, String date) {
    }

    /**
     * available = 저장소 위치를 알고 git 저장소다. behind = 받을 새 커밋 수. dirty = 커밋 안 한 변경이 있어 자동 업데이트를 막음
     */
    public record Status(boolean available, String repoDir, String branch, Commit current, int behind, List<Commit> commits,
                         boolean dirty, String checkedAt, String error, boolean updating) {
    }

    public Status status() {
        Status s = cached;
        return s == null ? check() : withUpdating(s);
    }

    /** 6시간마다 원격을 확인해 둔다(사이드바 «새 버전» 표시용) */
    @Scheduled(initialDelay = 120_000, fixedDelay = 6 * 3_600_000)
    public void scheduledCheck() {
        check();
    }

    public synchronized Status check() {
        if (repo == null || !Files.isDirectory(repo.resolve(".git"))) {
            cached = new Status(false, repo == null ? null : repo.toString(), null, null, 0, List.of(), false, Instant.now().toString(),
                    repo == null ? "설치 스크립트로 한 번 다시 설치하면 켜져요(저장소 위치를 몰라요)" : "git 저장소가 아니에요: " + repo, updating);
            return cached;
        }
        try {
            String branch = git(15, "rev-parse", "--abbrev-ref", "HEAD");
            Commit current = parse(git(15, "log", "-1", "--format=%h%x09%s%x09%cI")).stream().findFirst().orElse(null);
            String error = null;
            try {
                remote(45, "fetch", "-q", "origin");
            } catch (IOException e) {
                error = "원격 확인 실패: " + e.getMessage();
            }
            int behind = 0;
            List<Commit> commits = List.of();
            try {
                behind = Integer.parseInt(git(15, "rev-list", "--count", "HEAD..@{u}").strip());
                commits = parse(git(15, "log", "-n", "20", "--format=%h%x09%s%x09%cI", "HEAD..@{u}"));
            } catch (IOException | NumberFormatException e) {
                error = error != null ? error : "따라가는 원격 브랜치가 없어요";
            }
            boolean dirty = git(15, "status", "--porcelain", "--untracked-files=no").lines()
                    .anyMatch(l -> !l.strip().endsWith(GENERATED));
            cached = new Status(true, repo.toString(), branch.strip(), current, behind, commits, dirty, Instant.now().toString(), error, updating);
        } catch (IOException e) {
            cached = new Status(true, repo.toString(), null, null, 0, List.of(), false, Instant.now().toString(), e.getMessage(), updating);
        }
        return cached;
    }

    /** git pull 후 설치 스크립트를 따로 띄운다. 스크립트가 이 서버를 내렸다 새 버전으로 다시 띄운다 */
    public synchronized String apply() throws IOException {
        if (updating) {
            return "이미 업데이트 중이에요";
        }
        Status s = check();
        if (!s.available()) {
            throw new IllegalStateException(s.error());
        }
        if (s.dirty()) {
            throw new IllegalStateException("저장소에 커밋 안 한 변경이 있어 자동 업데이트를 멈췄어요 — " + repo);
        }
        if (s.behind() == 0) {
            return "이미 최신이에요";
        }
        try {
            git(15, "checkout", "--", GENERATED);
        } catch (IOException ignore) {
            // 그 파일이 없는 저장소도 있다
        }
        remote(120, "pull", "--ff-only", "-q");
        Files.createDirectories(logs);
        File logFile = logs.resolve("update.log").toFile();
        List<String> cmd = WINDOWS
                ? List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", repo.resolve("install.ps1").toString())
                : List.of("/usr/bin/perl", "-MPOSIX", "-e", "POSIX::setsid(); exec @ARGV", "/bin/bash", "-c", "sleep 2; exec ./install.sh");
        new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile)).start();
        updating = true;
        cached = withUpdating(s);
        log.info("업데이트 시작: {} 커밋, 설치 스크립트 실행 ({})", s.behind(), cmd.getLast());
        return "새 버전 " + s.behind() + "개를 받았어요. 다시 빌드해 재시작하는 데 1~2분 걸려요";
    }

    private Status withUpdating(Status s) {
        return new Status(s.available(), s.repoDir(), s.branch(), s.current(), s.behind(), s.commits(), s.dirty(), s.checkedAt(),
                s.error(), updating);
    }

    static List<Commit> parse(String out) {
        List<Commit> list = new ArrayList<>();
        for (String l : out.split("\n")) {
            String[] p = l.split("\t", 3);
            if (p.length == 3) {
                list.add(new Commit(p[0], p[1], p[2]));
            }
        }
        return list;
    }

    /**
     * 원격이 필요한 명령(fetch·pull). 공개 저장소는 그냥 되고, 비공개 저장소는 백그라운드 서비스라 비밀번호를 못 물으므로
     * 인증 실패면 gh 로그인 정보로 한 번 더 해 본다
     */
    private String remote(int timeoutSec, String... args) throws IOException {
        try {
            return git(timeoutSec, args);
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            if (!m.contains("Username") && !m.contains("Authentication") && !m.contains("could not read")) {
                throw e;
            }
            List<String> withGh = new ArrayList<>(List.of("-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential"));
            withGh.addAll(List.of(args));
            return git(timeoutSec, withGh.toArray(String[]::new));
        }
    }

    private String git(int timeoutSec, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repo.toString());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        try {
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git " + args[0] + " 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("중단됨");
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            throw new IOException(out.lines().reduce((a, b) -> b).orElse("git " + args[0] + " 실패").strip());
        }
        return out;
    }
}
