package com.worklog.collect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.worklog.config.WorklogProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 세션에서 만난 저장소들의 내 커밋(모든 브랜치, 머지 제외)을 모은다.
 * 세션 원본은 30일 뒤 지워지지만 커밋은 git 에 남아 있어 git-lookback-days 만큼 소급한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitCommitCollector {

    private static final char SEP = '\u001f';

    private final WorklogProperties props;
    private final IngestStore store;
    private final GitCli git;

    public int collectAll() {
        int added = 0;
        for (String root : store.repoRoots()) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            added += collect(dir);
        }
        return added;
    }

    private int collect(Path root) {
        String repo = String.valueOf(root.getFileName());
        Optional<String> author = git.run(root, List.of("config", "user.name")).map(String::strip).filter(s -> !s.isEmpty());
        if (author.isEmpty()) {
            return 0;
        }
        // 마지막으로 본 커밋 하루 전부터 다시 훑는다(다른 브랜치에서 늦게 들어온 커밋 대비). 중복은 PK 가 거른다
        Instant since = store.latestCommitAt(repo)
                .map(at -> Instant.parse(at).minus(1, ChronoUnit.DAYS))
                .orElseGet(() -> Instant.now().minus(props.gitLookbackDays(), ChronoUnit.DAYS));
        Optional<String> out = git.run(root, List.of("log", "--all", "--no-merges",
                "--author=" + author.get(), "--since=" + since,
                "--pretty=format:%H" + SEP + "%aI" + SEP + "%s"));
        if (out.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (String line : out.get().split("\n")) {
            String[] f = line.split(String.valueOf(SEP), 3);
            if (f.length < 3) {
                continue;
            }
            OffsetDateTime at = OffsetDateTime.parse(f[1]);
            added += store.insertCommit(repo, f[0], at.toInstant(),
                    LocalDate.ofInstant(at.toInstant(), props.zone()), f[2]);
        }
        if (added > 0) {
            log.info("커밋 {}건 추가: {}", added, repo);
        }
        return added;
    }
}
