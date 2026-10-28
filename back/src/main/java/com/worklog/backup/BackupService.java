package com.worklog.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.worklog.config.WorklogProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 하루 한 번 DB 스냅샷. 돌고 있는 DB 를 VACUUM INTO 로 일관되게 떠서 gzip 으로 이 컴퓨터에 두고,
 * macOS 면 openssl(AES-256-CBC, PBKDF2 20만 회)로 암호화한 사본을 iCloud Drive 에 하나 더 둔다(암호는 키체인).
 * 앱 없이도 restore.sh / restore.ps1 로 되살린다. Windows 는 암호화 사본 없이 로컬 백업만.
 * 보관: 최근 일간 N개 + 주별 마지막 것 N개 + 월별 마지막 것 N개.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    static final String OPENSSL = "/usr/bin/openssl";
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    private static final Pattern NAME = Pattern.compile("worklog-(\\d{4}-\\d{2}-\\d{2})\\.db\\.gz(\\.enc)?");
    private static final String ACCOUNT = "worklog";

    private final WorklogProperties props;
    private final JdbcClient jdbc;
    private final ReentrantLock lock = new ReentrantLock();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (enabled() && !Files.exists(localFile(today()))) {
            runQuietly();
        }
    }

    @Scheduled(cron = "0 30 12 * * *", zone = "${worklog.zone}")
    public void daily() {
        if (enabled()) {
            runQuietly();
        }
    }

    private void runQuietly() {
        try {
            backupNow();
        } catch (Exception e) {
            log.error("DB 백업 실패", e);
        }
    }

    /** 오늘 날짜 파일을 새로 만든다(같은 날 다시 하면 덮어씀) */
    public Result backupNow() throws IOException, InterruptedException {
        if (!lock.tryLock()) {
            throw new IllegalStateException("백업이 이미 진행 중");
        }
        try {
            WorklogProperties.Backup b = props.backup();
            Files.createDirectories(b.localDir());
            LocalDate day = today();
            Path snapshot = b.localDir().resolve(".snapshot.db");
            Files.deleteIfExists(snapshot);
            jdbc.sql("VACUUM INTO '" + snapshot.toString().replace("'", "''") + "'").update();

            Path gz = localFile(day);
            Path tmp = gz.resolveSibling(gz.getFileName() + ".tmp");
            try (InputStream in = Files.newInputStream(snapshot);
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                in.transferTo(out);
            }
            Files.move(tmp, gz, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(snapshot);
            prune(b.localDir(), false);

            Path mirrored = null;
            // 암호화 사본은 macOS 에서만(키체인·openssl). 다른 OS 는 로컬 백업만
            if (MAC && Files.isExecutable(Path.of(OPENSSL)) && b.mirrorDir() != null && !b.mirrorDir().toString().isBlank()) {
                mirrored = mirror(gz, day, b);
                prune(b.mirrorDir(), true);
            }
            log.info("DB 백업 완료: {} ({}KB){}", gz.getFileName(), Files.size(gz) / 1024,
                    mirrored == null ? "" : ", 암호화 사본 → " + mirrored);
            return new Result(gz.toString(), mirrored == null ? null : mirrored.toString(), Files.size(gz));
        } finally {
            lock.unlock();
        }
    }

    private Path mirror(Path gz, LocalDate day, WorklogProperties.Backup b) throws IOException, InterruptedException {
        Files.createDirectories(b.mirrorDir());
        String password = password(b.keychainService());
        Path enc = b.mirrorDir().resolve("worklog-" + day + ".db.gz.enc");
        Path tmp = b.mirrorDir().resolve(".worklog-" + day + ".tmp");
        ProcessBuilder pb = new ProcessBuilder(OPENSSL, "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "200000", "-salt",
                "-pass", "env:WORKLOG_BACKUP_PW", "-in", gz.toString(), "-out", tmp.toString());
        pb.environment().put("WORKLOG_BACKUP_PW", password);
        run(pb, "openssl 암호화");
        Files.move(tmp, enc, StandardCopyOption.REPLACE_EXISTING);
        return enc;
    }

    /** 키체인에서 백업 암호를 읽고, 없으면 만들어 넣는다 */
    String password(String service) throws IOException, InterruptedException {
        Optional<String> existing = runOut(new ProcessBuilder("/usr/bin/security", "find-generic-password",
                "-s", service, "-a", ACCOUNT, "-w"));
        if (existing.isPresent() && !existing.get().isBlank()) {
            return existing.get().strip();
        }
        String generated = generatePassword();
        run(new ProcessBuilder("/usr/bin/security", "add-generic-password", "-s", service, "-a", ACCOUNT,
                "-w", generated, "-U"), "키체인 저장");
        log.warn("백업 암호를 새로 만들어 키체인('{}')에 저장함 — ./backup-password.sh 로 확인해 따로 보관할 것", service);
        return generated;
    }

    private static String generatePassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            if (i > 0 && i % 6 == 0) {
                sb.append('-');
            }
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 일간 최근 N개 + ISO 주별 마지막 것 + 월별 마지막 것만 남긴다 */
    void prune(Path dir, boolean encrypted) throws IOException {
        WorklogProperties.Backup b = props.backup();
        List<Map.Entry<LocalDate, Path>> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                Matcher m = NAME.matcher(p.getFileName().toString());
                if (m.matches() && (m.group(2) != null) == encrypted) {
                    files.add(Map.entry(LocalDate.parse(m.group(1)), p));
                }
            }
        }
        files.sort(Map.Entry.<LocalDate, Path>comparingByKey().reversed());
        Set<Path> keep = new HashSet<>();
        Set<String> weeks = new HashSet<>();
        Set<String> months = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            LocalDate d = files.get(i).getKey();
            Path p = files.get(i).getValue();
            if (i < b.keepDaily()) {
                keep.add(p);
            }
            String week = d.get(IsoFields.WEEK_BASED_YEAR) + "-" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            if (weeks.size() < b.keepWeekly() && weeks.add(week)) {
                keep.add(p);
            }
            String month = d.getYear() + "-" + d.getMonthValue();
            if (months.size() < b.keepMonthly() && months.add(month)) {
                keep.add(p);
            }
        }
        for (Map.Entry<LocalDate, Path> f : files) {
            if (!keep.contains(f.getValue())) {
                Files.deleteIfExists(f.getValue());
                log.info("오래된 백업 정리: {}", f.getValue().getFileName());
            }
        }
    }

    public List<BackupFile> list() throws IOException {
        WorklogProperties.Backup b = props.backup();
        Set<String> mirroredDays = new HashSet<>();
        if (b.mirrorDir() != null && Files.isDirectory(b.mirrorDir())) {
            try (Stream<Path> s = Files.list(b.mirrorDir())) {
                s.map(p -> NAME.matcher(p.getFileName().toString())).filter(Matcher::matches)
                        .filter(m -> m.group(2) != null).forEach(m -> mirroredDays.add(m.group(1)));
            }
        }
        List<BackupFile> result = new ArrayList<>();
        if (Files.isDirectory(b.localDir())) {
            try (Stream<Path> s = Files.list(b.localDir())) {
                for (Path p : s.toList()) {
                    Matcher m = NAME.matcher(p.getFileName().toString());
                    if (m.matches() && m.group(2) == null) {
                        result.add(new BackupFile(m.group(1), p.getFileName().toString(), Files.size(p),
                                Files.getLastModifiedTime(p).toInstant().toString(), mirroredDays.contains(m.group(1))));
                    }
                }
            }
        }
        result.sort(Comparator.comparing(BackupFile::day).reversed());
        return result;
    }

    private boolean enabled() {
        return props.backup() != null && props.backup().enabled();
    }

    private LocalDate today() {
        return LocalDate.now(props.zone());
    }

    private Path localFile(LocalDate day) {
        return props.backup().localDir().resolve("worklog-" + day + ".db.gz");
    }

    private static void run(ProcessBuilder pb, String what) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException(what + " 실패: " + out.strip());
        }
    }

    private static Optional<String> runOut(ProcessBuilder pb) throws IOException, InterruptedException {
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.getErrorStream().readAllBytes();
        return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 ? Optional.of(out) : Optional.empty();
    }

    public record Result(String local, String mirrored, long size) {
    }

    public record BackupFile(String day, String name, long size, String at, boolean mirrored) {
    }

    /** 화면 표시용 — 마지막 백업 시각 */
    public Optional<Instant> lastBackupAt() throws IOException {
        return list().stream().findFirst().map(f -> Instant.parse(f.at()));
    }
}
