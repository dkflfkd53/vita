package com.worklog.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.worklog.collect.GitCli;
import com.worklog.config.Settings.ProjectRule;

import lombok.RequiredArgsConstructor;

/**
 * 어떤 세션·커밋을 관리 대상으로 볼지와 화면 표시 이름(카테고리). 기준은 설정의 "폴더 → 카테고리" 규칙.
 * <ul>
 *   <li>경로 규칙(`~/work/회사A`, `/Users/me/code/app` 처럼 / 나 \ 가 든 값): 세션 폴더에서 위로 올라가며 <b>가장 가까운(가장 긴)</b> 규칙.
 *       그 아래 새 저장소·git 이 아닌 폴더도 따라온다</li>
 *   <li>이름 규칙(`my-app`): 세션이 속한 git 저장소의 폴더 이름이 같으면. 경로 규칙에 안 걸렸을 때만 본다</li>
 * </ul>
 * 규칙이 하나도 없으면 전부 관리하고 표시 이름은 저장소 이름 그대로. 하나라도 있으면 걸린 것만 관리한다.
 */
@Component
@RequiredArgsConstructor
public class ProjectScope {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private final SettingsService settings;
    private final GitCli git;
    private final JdbcClient jdbc;

    /** 한 규칙 — path 가 있으면 경로 규칙, 없으면 이름 규칙 */
    private record Rule(Path path, String name, String category) {
    }

    private List<Rule> rules() {
        List<Rule> list = new ArrayList<>();
        for (ProjectRule r : settings.projects()) {
            String v = r.repo().strip();
            boolean isPath = v.contains("/") || v.contains("\\") || v.startsWith("~");
            list.add(isPath ? new Rule(normalize(expand(v)), null, r.category()) : new Rule(null, v, r.category()));
        }
        // 긴 경로가 먼저 — 가장 가까운 상위 폴더가 이긴다
        list.sort(Comparator.comparingInt((Rule r) -> r.path() == null ? -1 : r.path().getNameCount()).reversed());
        return list;
    }

    /** 세션·저장소의 카테고리. dir = 세션 작업 폴더(또는 저장소 루트), repoName = 그 폴더가 속한 저장소 이름 */
    public Optional<String> resolve(String dir, String repoName) {
        List<Rule> rules = rules();
        if (rules.isEmpty()) {
            return Optional.ofNullable(repoName);
        }
        if (dir != null && !dir.isBlank()) {
            Path p = normalize(Path.of(dir));
            for (Rule r : rules) {
                if (r.path() != null && p.startsWith(r.path())) {
                    return Optional.of(r.category());
                }
            }
        }
        for (Rule r : rules) {
            if (r.name() != null && r.name().equals(repoName)) {
                return Optional.of(r.category());
            }
        }
        return Optional.empty();
    }

    // ───────── 세션 ─────────

    public boolean managed(String cwd, String repoName) {
        return resolve(cwd, repoName).isPresent();
    }

    /** 표시 이름(카테고리). 관리 대상이 아니면 저장소 이름 그대로 */
    public String label(String cwd, String repoName) {
        return resolve(cwd, repoName).orElse(repoName);
    }

    /** 아직 수집 전인 막 연 세션 — 작업 폴더로 판정 */
    public Optional<String> labelForCwd(String cwd) {
        if (cwd == null) {
            return Optional.empty();
        }
        Path dir = Path.of(cwd);
        String repo = git.toplevel(dir).map(p -> String.valueOf(p.getFileName())).orElse(String.valueOf(dir.getFileName()));
        return resolve(cwd, repo);
    }

    // ───────── 커밋·저장소(이름만 아는 것) ─────────

    /** 저장소 이름 → 루트 경로(수집하며 본 것). 같은 이름이 여럿이면 하나라도 관리 대상이면 관리 */
    private Map<String, List<String>> roots() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        jdbc.sql("SELECT name, root FROM repo ORDER BY name, root").query((rs, i) -> {
            m.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            return null;
        }).list();
        return m;
    }

    public boolean managedRepo(String repoName) {
        return repoResolve(repoName).isPresent();
    }

    public String repoLabel(String repoName) {
        return repoResolve(repoName).orElse(repoName);
    }

    private Optional<String> repoResolve(String repoName) {
        List<String> paths = roots().getOrDefault(repoName, List.of());
        if (paths.isEmpty()) {
            return resolve(null, repoName);
        }
        for (String root : paths) {
            Optional<String> c = resolve(root, repoName);
            if (c.isPresent()) {
                return c;
            }
        }
        return Optional.empty();
    }

    /** 관리하는 저장소 이름들(규칙이 없으면 본 저장소 전부) */
    public Set<String> repos() {
        Set<String> out = new LinkedHashSet<>();
        roots().forEach((name, paths) -> {
            if (paths.stream().anyMatch(root -> resolve(root, name).isPresent())) {
                out.add(name);
            }
        });
        return out;
    }

    /** 규칙에 쓰인 카테고리들(화면의 범위 선택지) */
    public Set<String> labels() {
        Set<String> out = new LinkedHashSet<>();
        settings.projects().forEach(r -> out.add(r.category()));
        return out;
    }

    // ───────── 경로 ─────────

    static Path expand(String v) {
        String home = System.getProperty("user.home");
        if (v.equals("~")) {
            return Path.of(home);
        }
        if (v.startsWith("~/") || v.startsWith("~\\")) {
            return Path.of(home, v.substring(2));
        }
        return Path.of(v);
    }

    /** 비교용 — 절대경로·정규화, Windows 는 대소문자 무시 */
    static Path normalize(Path p) {
        Path n = p.toAbsolutePath().normalize();
        return WINDOWS ? Path.of(n.toString().toLowerCase(Locale.ROOT)) : n;
    }
}
