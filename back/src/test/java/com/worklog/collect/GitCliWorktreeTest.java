package com.worklog.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** worktree(Codex 앱·Claude Code 의 작업 사본)는 원래 저장소 루트로 본다 */
class GitCliWorktreeTest {

    @TempDir
    Path dir;

    @Test
    void worktreeResolvesToMainCheckout() throws Exception {
        GitCli git = new GitCli();
        Path main = Files.createDirectories(dir.resolve("work/app"));
        git.run(main, List.of("init", "-q")).orElseThrow();
        Files.writeString(main.resolve("a.txt"), "a");
        git.run(main, List.of("add", ".")).orElseThrow();
        git.run(main, List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init")).orElseThrow();
        Path wt = dir.resolve("codex/worktrees/1a2b/app");
        git.run(main, List.of("worktree", "add", "-q", "-b", "task", wt.toString())).orElseThrow();
        Files.createDirectories(wt.resolve("src"));

        Path real = main.toRealPath();
        assertThat(git.toplevel(main).map(this::real)).contains(real);
        assertThat(git.toplevel(wt.resolve("src")).map(this::real)).contains(real);
        assertThat(git.toplevel(dir.resolve("codex"))).isEmpty();
    }

    private Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (Exception e) {
            return p;
        }
    }
}
