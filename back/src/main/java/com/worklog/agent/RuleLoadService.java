package com.worklog.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worklog.agent.ObserverViews.DietItem;
import com.worklog.agent.ObserverViews.DietLog;
import com.worklog.agent.ObserverViews.RuleLoad;
import com.worklog.agent.ObserverViews.Target;
import com.worklog.config.ProjectScope;
import com.worklog.config.WorklogProperties;
import com.worklog.summary.ClaudeCli;
import com.worklog.summary.JobService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 규칙 무게: md·메모리에 넣은 건 매 세션 같이 읽힌다. 넣을 곳마다 얼마나 읽히는지 보여 주고(①),
 * Vita 규칙이 상한을 넘으면 새로 넣기 전에 빼기·합치기를 권하며(②), 긴 메모리 색인 줄을 짧게 줄이는 정리안을 만든다(③).
 * <p>
 * 색인 다이어트: Claude 는 MEMORY.md 색인 줄의 제목·설명만 보고 관련 있을 때 링크 문서를 연다. 그래서 줄은 "언제 열지" 단서만 남기고
 * 세부는 링크 문서로 옮긴다. 적용은 사용자가 고른 줄만, 쓰기 전에 백업하고, 그 사이 줄이 바뀌었으면 건너뛴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleLoadService {

    static final int BUDGET = 10;
    static final int LONG_LINE = 150;
    private static final int DIET_MAX = 30;
    private static final int FILE_PREVIEW = 1500;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+\\.md)\\)");
    private static final Pattern VITA_BLOCK = Pattern.compile("<!-- vita:insight-(\\d+) -->[\\s\\S]*?<!-- /vita:insight-\\1 -->");

    private final InsightTargets targets;
    private final JdbcClient jdbc;
    private final ClaudeCli claude;
    private final JobService jobs;
    private final WorklogProperties props;
    private final ProjectScope scope;

    // ───────── ① 읽히는 양 · ② 예산 ─────────

    public List<RuleLoad> load() {
        List<RuleLoad> out = new ArrayList<>();
        for (Target t : targets.list()) {
            Path f = loadedFile(t);
            if (!Files.exists(f) || hiddenRepo(t.key())) {
                continue;
            }
            String s = read(f);
            int vitaRules = 0;
            int vitaChars = 0;
            int longLines = 0;
            if ("memory".equals(t.kind())) {
                for (String l : s.split("\n")) {
                    if (l.contains("feedback_vita_insight_")) {
                        vitaRules++;
                        vitaChars += l.length() + 1;
                    } else if (l.length() > LONG_LINE && LINK.matcher(l).find()) {
                        longLines++;
                    }
                }
            } else {
                Matcher m = VITA_BLOCK.matcher(s);
                while (m.find()) {
                    vitaRules++;
                    vitaChars += m.group().length() + 1;
                }
            }
            int pending = jdbc.sql("SELECT COUNT(*) FROM memory_diet WHERE target = ? AND status = 'proposed'").param(t.key())
                    .query(Integer.class).single();
            out.add(new RuleLoad(t.key(), t.label(), t.kind(), tilde(f), s.length(), (int) s.lines().count(), estTokens(s),
                    vitaRules, vitaChars, longLines, BUDGET, pending));
        }
        out.sort(Comparator.comparingInt(RuleLoad::chars).reversed());
        return out;
    }

    /** 한글·한자·가나는 1자≈1토큰, 그 밖(영문·숫자·기호·공백)은 3.5자≈1토큰으로 어림 */
    static int estTokens(String s) {
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3130 && c <= 0x318F) || (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3040 && c <= 0x30FF)) {
                cjk++;
            } else {
                other++;
            }
        }
        return (int) Math.round(cjk + other / 3.5);
    }

    /** AI 제안 프롬프트에 붙일 넣을 곳별 무게 한 줄씩 */
    public String summaryForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (RuleLoad r : load()) {
            sb.append("- ").append(r.target()).append(": 매 세션 약 ").append(r.estTokens()).append("토큰, Vita 규칙 ")
                    .append(r.vitaRules()).append('/').append(r.budget())
                    .append(r.vitaRules() >= r.budget() ? " — 상한, 이곳엔 새 규칙 대신 기존 규칙 고치기·합치기만" : "").append('\n');
        }
        return sb.toString();
    }

    // ───────── ③ 색인 다이어트 ─────────

    public List<DietItem> diet(String target) {
        return jdbc.sql("""
                SELECT id, target, file, old_line, new_line, moved, reason, status FROM memory_diet
                WHERE target = ? AND status = 'proposed' ORDER BY id
                """).param(target).query((rs, i) -> new DietItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(4).length() - rs.getString(5).length(), estTokens(rs.getString(4)) - estTokens(rs.getString(5)))).list();
    }

    public List<DietLog> dietLog(String target) {
        return jdbc.sql("""
                SELECT target, applied_at, lines, before_chars, after_chars, before_tokens, after_tokens, backup
                FROM memory_diet_log WHERE target = ? ORDER BY id DESC LIMIT 5
                """).param(target).query((rs, i) -> new DietLog(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8))).list();
    }

    public JobService.Job submitDiet(String target) {
        Target t = memoryTarget(target);
        return jobs.submit("observer:diet:" + target, "메모리 색인 줄이기 제안", progress -> {
            Path index = loadedFile(t);
            Path dir = index.getParent();
            List<String> lines = read(index).lines().toList();
            List<String> cands = lines.stream().filter(l -> l.length() > LONG_LINE && linkOf(l) != null)
                    .filter(l -> Files.exists(dir.resolve(linkOf(l))))
                    .sorted(Comparator.comparingInt(String::length).reversed()).limit(DIET_MAX).toList();
            if (cands.isEmpty()) {
                return "줄일 만한 긴 색인 줄이 없어요";
            }
            progress.update("Claude 가 긴 색인 줄 " + cands.size() + "개를 줄이는 중");
            String out = claude.ask(dietPrompt(cands, dir));
            List<String[]> items = parseDiet(out, cands);
            jdbc.sql("DELETE FROM memory_diet WHERE target = ? AND status = 'proposed'").param(target).update();
            String now = Instant.now().toString();
            for (String[] it : items) {
                jdbc.sql("""
                        INSERT INTO memory_diet (target, created_at, file, old_line, new_line, moved, reason, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'proposed')
                        """).params(target, now, linkOf(it[0]), it[0], it[1], it[2], it[3]).update();
            }
            int saved = items.stream().mapToInt(it -> estTokens(it[0]) - estTokens(it[1])).sum();
            return "줄이기 제안 " + items.size() + "줄 · 다 적용하면 매 세션 약 " + saved + "토큰 덜 읽음";
        });
    }

    private String dietPrompt(List<String> cands, Path dir) {
        StringBuilder sb = new StringBuilder("""
                너는 Claude Code 자동 메모리 색인(MEMORY.md) 정리 담당이다. 이 색인은 매 세션 통째로 읽히고,
                Claude 는 각 줄의 제목·설명만 보고 지금 일과 관련 있으면 링크된 문서를 연다. 그래서 색인 줄은
                "언제 이 문서를 열어야 하는지" 단서만 있으면 되고, 세부(수치·경로·절차·이력)는 링크 문서에 있으면 된다.

                아래 긴 줄마다 짧은 새 줄을 만든다.
                규칙
                - 새 줄은 "- [제목](파일.md) — 단서" 형태. 링크 `(파일.md)` 는 글자 그대로 유지, 제목은 줄여도 된다.
                - 단서는 120자 안팎(링크 포함). 찾아 들어올 낱말(시스템·화면·테이블 이름 등)과 사고·금지·필수 같은 경고는 남긴다.
                - 새 줄에서 빠지는 내용 중 링크 문서(아래 '문서 앞부분')에 이미 없는 사실은 moved 에 그대로 적는다(문서 끝에 옮겨 적는다).
                  문서에 이미 있으면 moved 는 빈 문자열. 사실을 바꾸거나 새로 지어내지 않는다.
                - 이미 충분히 짧거나 줄이면 단서가 사라지는 줄은 건너뛴다(출력에서 뺀다).
                - reason = 무엇을 뺐는지 한 줄.

                출력은 JSON 배열 하나만(설명·코드블록 표시 없이):
                [{"n":번호,"newLine":"- [제목](파일.md) — 단서","moved":"문서로 옮길 사실 또는 빈 문자열","reason":"한 줄"}]

                """);
        for (int i = 0; i < cands.size(); i++) {
            String l = cands.get(i);
            String doc = read(dir.resolve(linkOf(l)));
            sb.append("## ").append(i + 1).append('\n').append("줄: ").append(l).append('\n')
                    .append("문서 앞부분(").append(linkOf(l)).append("):\n")
                    .append(doc.length() > FILE_PREVIEW ? doc.substring(0, FILE_PREVIEW) + "…" : doc).append("\n\n");
        }
        return sb.toString();
    }

    /** @return [old, new, moved, reason] — 링크가 그대로이고 실제로 짧아진 것만 */
    static List<String[]> parseDiet(String out, List<String> cands) throws IOException {
        int s = out.indexOf('[');
        int e = out.lastIndexOf(']');
        if (s < 0 || e <= s) {
            throw new IOException("정리안을 JSON 으로 받지 못함: " + out.substring(0, Math.min(200, out.length())));
        }
        List<String[]> list = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (JsonNode n : MAPPER.readTree(out.substring(s, e + 1))) {
            int i = n.path("n").asInt(0) - 1;
            if (i < 0 || i >= cands.size() || !seen.add(i)) {
                continue;
            }
            String old = cands.get(i);
            String nl = n.path("newLine").asText("").replaceAll("\\s*\\n\\s*", " ").strip();
            if (!nl.startsWith("- ") || !nl.contains("(" + linkOf(old) + ")") || nl.length() >= old.length() - 10) {
                continue;
            }
            String moved = n.path("moved").asText("").strip();
            list.add(new String[] {old, nl, moved.isEmpty() ? null : moved, n.path("reason").asText(null)});
        }
        return list;
    }

    /** 고른 제안만 적용. 쓰기 전에 색인과 바뀌는 문서를 exports 에 백업 */
    public String applyDiet(String target, List<Long> ids) throws IOException {
        Target t = memoryTarget(target);
        Path index = loadedFile(t);
        Path dir = index.getParent();
        List<DietItem> chosen = diet(target).stream().filter(d -> ids.contains(d.id())).toList();
        if (chosen.isEmpty()) {
            return "고른 제안이 없어요";
        }
        Path backup = props.exportsDir().resolve("memory-diet-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(backup);
        Files.copy(index, backup.resolve("MEMORY.md"));
        String before = read(index);
        List<String> lines = new ArrayList<>(read(index).lines().toList());
        int applied = 0;
        int skipped = 0;
        for (DietItem d : chosen) {
            int at = lines.indexOf(d.oldLine());
            if (at < 0) {
                skipped++;
                jdbc.sql("UPDATE memory_diet SET status = 'skipped' WHERE id = ?").param(d.id()).update();
                continue;
            }
            lines.set(at, d.newLine());
            if (d.moved() != null && !d.moved().isBlank()) {
                Path doc = dir.resolve(d.file()).normalize();
                if (doc.startsWith(dir) && Files.exists(doc)) {
                    if (!Files.exists(backup.resolve(d.file()))) {
                        Files.copy(doc, backup.resolve(d.file()));
                    }
                    String cur = read(doc);
                    Files.writeString(doc, (cur.endsWith("\n") ? cur : cur + "\n") + "\n## 색인에서 옮긴 내용 (Vita, " + LocalDate.now()
                            + ")\n" + d.moved().strip() + "\n", StandardCharsets.UTF_8);
                }
            }
            applied++;
            jdbc.sql("UPDATE memory_diet SET status = 'applied' WHERE id = ?").param(d.id()).update();
        }
        String after = String.join("\n", lines) + "\n";
        Files.writeString(index, after, StandardCharsets.UTF_8);
        if (applied > 0) {
            jdbc.sql("""
                    INSERT INTO memory_diet_log (target, applied_at, lines, before_chars, after_chars, before_tokens, after_tokens, backup)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(target, Instant.now().toString(), applied, before.length(), after.length(), estTokens(before),
                    estTokens(after), tilde(backup)).update();
        }
        log.info("메모리 색인 줄이기 {}: {}줄 적용, {}줄 건너뜀, 백업 {}", target, applied, skipped, backup);
        int pct = before.isEmpty() ? 0 : Math.round(100f * (before.length() - after.length()) / before.length());
        return applied + "줄 줄임 · 색인 약 " + estTokens(before) + " → " + estTokens(after) + "토큰(-" + pct + "%)" + (skipped > 0 ? " · " + skipped + "줄은 그 사이 바뀌어 건너뜀" : "")
                + " · 백업 " + tilde(backup);
    }

    public void dismissDiet(long id) {
        jdbc.sql("UPDATE memory_diet SET status = 'dismissed' WHERE id = ? AND status = 'proposed'").param(id).update();
    }

    // ───────── 내부 ─────────

    /** 숨긴 카테고리 저장소는 무게 표에서 뺀다 */
    private boolean hiddenRepo(String key) {
        int c = key.indexOf(':');
        return c > 0 && scope.hidden(scope.repoLabel(key.substring(c + 1)));
    }

    private Target memoryTarget(String key) {
        Target t = targets.find(key).orElseThrow(() -> new IllegalArgumentException("모르는 넣을 곳: " + key));
        if (!"memory".equals(t.kind())) {
            throw new IllegalArgumentException("색인 줄이기는 메모리만 됩니다: " + key);
        }
        return t;
    }

    private static Path loadedFile(Target t) {
        Path p = InsightTargets.untilde(t.path());
        return "memory".equals(t.kind()) ? p.resolve("MEMORY.md") : p;
    }

    static String linkOf(String line) {
        Matcher m = LINK.matcher(line);
        if (!m.find()) {
            return null;
        }
        String f = m.group(1);
        return f.contains("/") || f.startsWith("http") ? null : f;
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
