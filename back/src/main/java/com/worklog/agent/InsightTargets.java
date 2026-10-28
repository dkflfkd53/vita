package com.worklog.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.worklog.agent.ObserverViews.Target;
import com.worklog.config.ProjectScope;
import com.worklog.config.WorklogProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 개선 제안을 실제로 적어 넣을 곳. Claude Code 가 매 세션 읽어 들이는 파일들이다.
 * <ul>
 *   <li>global — ~/.claude/CLAUDE.md (모든 프로젝트)</li>
 *   <li>repo:이름 — 저장소 루트 CLAUDE.md (그 저장소에서만, 커밋 대상)</li>
 *   <li>memory:이름 — 그 저장소의 자동 메모리(~/.claude/projects/…/memory). feedback 파일 하나 + MEMORY.md 색인 한 줄</li>
 * </ul>
 * 쓰기는 덧붙이기만 한다. 넣은 자리에 표식(&lt;!-- vita:insight-번호 --&gt;)을 남겨 적용 확인·되돌리기에 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InsightTargets {

    private final WorklogProperties props;
    private final JdbcClient jdbc;
    private final ProjectScope scope;

    public List<Target> list() {
        List<Target> out = new ArrayList<>();
        Path global = props.claudeHome().resolve("CLAUDE.md");
        out.add(new Target("global", "전역 CLAUDE.md (모든 프로젝트)", tilde(global), Files.exists(global), "claude-md"));
        for (String repo : scope.repos()) {
            Optional<Path> root = repoRoot(repo);
            if (root.isEmpty()) {
                continue;
            }
            Path mem = memoryDir(root.get());
            if (Files.isDirectory(mem)) {
                out.add(new Target("memory:" + repo, repo + " 메모리 (feedback)", tilde(mem), true, "memory"));
            }
            Path md = root.get().resolve("CLAUDE.md");
            out.add(new Target("repo:" + repo, repo + "/CLAUDE.md (저장소, 커밋 대상)", tilde(md), Files.exists(md), "claude-md"));
        }
        return out;
    }

    public Optional<Target> find(String key) {
        return key == null ? Optional.empty() : list().stream().filter(t -> t.key().equals(key)).findFirst();
    }

    /** 지금 파일에 이 제안이 들어 있나 */
    public boolean applied(long id, String targetKey) {
        Optional<Path> p = file(id, targetKey);
        if (p.isEmpty() || !Files.exists(p.get())) {
            return false;
        }
        if (targetKey.startsWith("memory:")) {
            return true;
        }
        return read(p.get()).contains(marker(id));
    }

    /** @return 실제로 쓴 파일 */
    public Path apply(long id, String targetKey, String title, String problem, String snippet) throws IOException {
        Path file = file(id, targetKey).orElseThrow(() -> new IllegalArgumentException("적용할 곳을 고르세요"));
        String body = snippet == null ? "" : snippet.strip();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("넣을 문안이 비어 있습니다");
        }
        if (targetKey.startsWith("memory:")) {
            String md = """
                    ---
                    name: vita-insight-%d
                    description: %s
                    metadata:
                      type: feedback
                    ---

                    %s

                    **Why:** %s
                    **How to apply:** 위 규칙을 그대로 따른다. (Vita 에이전트 옵저버 제안 #%d, %s 도입)
                    """.formatted(id, oneLine(title), body, oneLine(problem == null ? title : problem), id, LocalDate.now());
            Files.writeString(file, md, StandardCharsets.UTF_8);
            Path index = file.getParent().resolve("MEMORY.md");
            String line = "- [%s](%s) — Vita 제안 #%d%n".formatted(oneLine(title), file.getFileName(), id);
            String cur = Files.exists(index) ? read(index) : "";
            if (!cur.contains(file.getFileName().toString())) {
                Files.writeString(index, (cur.isEmpty() || cur.endsWith("\n") ? cur : cur + "\n") + line, StandardCharsets.UTF_8);
            }
        } else {
            String cur = Files.exists(file) ? read(file) : "";
            if (!cur.contains(marker(id))) {
                String sep = cur.isEmpty() ? "" : cur.endsWith("\n\n") ? "" : cur.endsWith("\n") ? "\n" : "\n\n";
                Files.createDirectories(file.getParent());
                Files.writeString(file, cur + sep + marker(id) + "\n" + body + "\n" + endMarker(id) + "\n", StandardCharsets.UTF_8);
            }
        }
        log.info("개선 제안 #{} 적용: {}", id, file);
        return file;
    }

    /** 넣었던 문안을 뺀다(표식 사이 / 메모리 파일과 색인 줄) */
    public void revert(long id, String targetKey) throws IOException {
        Optional<Path> p = file(id, targetKey);
        if (p.isEmpty() || !Files.exists(p.get())) {
            return;
        }
        Path file = p.get();
        if (targetKey.startsWith("memory:")) {
            Files.delete(file);
            Path index = file.getParent().resolve("MEMORY.md");
            if (Files.exists(index)) {
                String name = file.getFileName().toString();
                List<String> kept = read(index).lines().filter(l -> !l.contains("(" + name + ")")).toList();
                Files.writeString(index, String.join("\n", kept) + "\n", StandardCharsets.UTF_8);
            }
        } else {
            String cur = read(file);
            String next = Pattern.compile("\\n?" + Pattern.quote(marker(id)) + "[\\s\\S]*?" + Pattern.quote(endMarker(id)) + "\\n?")
                    .matcher(cur).replaceAll("\n");
            next = next.replaceAll("\\n{3,}", "\n\n").replaceFirst("\\s+$", "");
            Files.writeString(file, next.isEmpty() ? "" : next + "\n", StandardCharsets.UTF_8);
        }
        log.info("개선 제안 #{} 되돌림: {}", id, file);
    }

    /** AI 제안 때 지금 규칙과 겹치지 않게 보여 줄 현재 내용(뒤쪽 위주로 잘라서) */
    public String currentRules(int maxPerFile) {
        StringBuilder sb = new StringBuilder();
        for (Target t : list()) {
            Path p = Path.of(t.path().replaceFirst("^~", System.getProperty("user.home")));
            Path f = "memory".equals(t.kind()) ? p.resolve("MEMORY.md") : p;
            if (!Files.exists(f)) {
                continue;
            }
            String s = read(f);
            if (s.length() > maxPerFile) {
                s = "…" + s.substring(s.length() - maxPerFile);
            }
            sb.append("### ").append(t.key()).append(" (").append(t.path()).append(")\n").append(s.strip()).append("\n\n");
        }
        return sb.toString();
    }

    private Optional<Path> file(long id, String targetKey) {
        if (targetKey == null) {
            return Optional.empty();
        }
        if ("global".equals(targetKey)) {
            return Optional.of(props.claudeHome().resolve("CLAUDE.md"));
        }
        int colon = targetKey.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String kind = targetKey.substring(0, colon);
        String repo = targetKey.substring(colon + 1);
        if (!scope.repos().contains(repo)) {
            return Optional.empty();
        }
        return repoRoot(repo).map(root -> switch (kind) {
            case "repo" -> root.resolve("CLAUDE.md");
            case "memory" -> memoryDir(root).resolve("feedback_vita_insight_%d.md".formatted(id));
            default -> null;
        });
    }

    private Optional<Path> repoRoot(String name) {
        return jdbc.sql("SELECT root FROM repo WHERE name = ? ORDER BY root LIMIT 1").param(name).query(String.class)
                .optional().map(Path::of);
    }

    /** Claude Code 의 프로젝트 폴더 이름 규칙: 경로의 영숫자 아닌 글자를 '-' 로 */
    private Path memoryDir(Path repoRoot) {
        return props.projectsDir().resolve(repoRoot.toString().replaceAll("[^A-Za-z0-9]", "-")).resolve("memory");
    }

    static String marker(long id) {
        return "<!-- vita:insight-" + id + " -->";
    }

    static String endMarker(long id) {
        return "<!-- /vita:insight-" + id + " -->";
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String tilde(Path p) {
        String home = System.getProperty("user.home");
        String s = p.toString();
        return s.startsWith(home) ? "~" + s.substring(home.length()) : s;
    }
}
