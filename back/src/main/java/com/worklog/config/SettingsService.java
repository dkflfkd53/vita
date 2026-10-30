package com.worklog.config;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worklog.config.Settings.Category;
import com.worklog.config.Settings.ProjectRule;
import com.worklog.config.Settings.ReportTemplate;
import com.worklog.config.Settings.RoutineRule;
import com.worklog.config.Settings.WorkTextConfig;
import com.worklog.config.Settings.WorkTextPart;

import lombok.RequiredArgsConstructor;

/**
 * 사용자 설정(setting 표, JSON) — 저장소→카테고리, 반복 업무 세션, 주간보고 갈래, 리포트 양식. 카테고리는 category 표.
 * 처음 읽을 때 application.yml 의 예전 값(worklog.projects·routine-sessions)이 있으면 그것으로 채운다.
 * 읽기가 잦아 메모리에 들고 있고, 바꿀 때만 다시 읽는다.
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> COLORS = Set.of("gray", "blue", "green", "yellow", "red", "purple", "orange", "teal", "pink");
    private static final Pattern PART_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,23}");

    /** 전체 업무 내용 기본값 — 사용자가 문체 예시·지시를 바꿀 수 있다 */
    static final WorkTextPart DEFAULT_FULL = new WorkTextPart("full", "전체 업무 내용", "items", List.of(), List.of(), List.of(), null,
            """
            이번 주 한 일 전체.
            - 첫 줄 "[업무 내용]", 빈 줄 하나, 그다음 카테고리마다 "- 카테고리" (설정의 카테고리 순서, 할 일 없는 카테고리는 뺀다)
            - 들여쓰기는 공백 4칸 단위. 업무는 4칸 "- 업무명 (부가정보)", 세부는 8칸 "- 내용"
            - 업무명 괄호: 요청자가 있으면 "(○○님 요청)", 진행률·대상·기간처럼 핵심 한 가지. 없으면 괄호 생략
            - 세부 내용: 업무당 1~4줄. 세부가 필요 없는 업무는 업무명 한 줄로 끝낸다
            """,
            """
            [업무 내용]

            - 프로젝트 A
                - 결제 모듈 리팩터링 (진행률 70%)
                    - 결제 승인 API 응답 구조 정리 및 오류 코드 통일
                    - 재시도 로직 추가, 중복 결제 방지 테스트 작성
            - 프로젝트 B
                - 관리자 화면 검색 속도 개선 (운영팀 요청)
                    - 목록 조회 인덱스 추가로 평균 응답 2.1초 → 0.3초
            """);

    private final JdbcClient jdbc;
    private final WorklogProperties props;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    // ───────── 읽기 ─────────

    public Settings.All all() {
        List<String> repos = jdbc.sql("SELECT DISTINCT name FROM repo ORDER BY name").query(String.class).list();
        return new Settings.All(categories(), projects(), routines(), workText(), reportTemplates(), ai(), repos, knownFolders());
    }

    /** 본 저장소 루트와 그 위 두 단계(홈 아래만) — 경로 규칙 고를 때 후보 */
    private List<String> knownFolders() {
        String home = System.getProperty("user.home");
        TreeSet<String> out = new TreeSet<>();
        for (String root : jdbc.sql("SELECT root FROM repo").query(String.class).list()) {
            Path p = Path.of(root);
            for (int up = 0; up < 3 && p != null && p.startsWith(home) && !p.equals(Path.of(home)); up++, p = p.getParent()) {
                out.add("~" + p.toString().substring(home.length()).replace('\\', '/'));
            }
        }
        return List.copyOf(out);
    }

    public List<Category> categories() {
        return jdbc.sql("SELECT name, color, subtypes FROM category ORDER BY sort, name")
                .query((rs, i) -> new Category(rs.getString(1), rs.getString(2), null, subtypesOf(rs.getString(3)))).list();
    }

    /** 카테고리 이름 → 세부 분류(있는 것만) */
    public Map<String, List<String>> subtypes() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        categories().forEach(c -> {
            if (!c.subtypes().isEmpty()) {
                m.put(c.name(), c.subtypes());
            }
        });
        return m;
    }

    private static List<String> subtypesOf(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public List<ProjectRule> projects() {
        return (List<ProjectRule>) cache.computeIfAbsent("projects", k -> read(k, new TypeReference<List<ProjectRule>>() { })
                .orElseGet(() -> {
                    List<ProjectRule> seed = new ArrayList<>();
                    if (props.projects() != null) {
                        props.projects().forEach((repo, label) -> seed.add(new ProjectRule(repo, label)));
                    }
                    return seed;
                }));
    }

    @SuppressWarnings("unchecked")
    public List<RoutineRule> routines() {
        return (List<RoutineRule>) cache.computeIfAbsent("routines", k -> read(k, new TypeReference<List<RoutineRule>>() { })
                .orElseGet(() -> props.routineSessions() == null ? List.of()
                        : props.routineSessions().stream().map(r -> new RoutineRule(r.title(), r.label(), r.color())).toList()));
    }

    public WorkTextConfig workText() {
        return (WorkTextConfig) cache.computeIfAbsent("work-text", k -> read(k, new TypeReference<WorkTextConfig>() { })
                .map(c -> new WorkTextConfig(c.full() == null ? DEFAULT_FULL : c.full(), c.parts() == null ? List.of() : c.parts()))
                .orElse(new WorkTextConfig(DEFAULT_FULL, List.of())));
    }

    @SuppressWarnings("unchecked")
    public List<ReportTemplate> reportTemplates() {
        return (List<ReportTemplate>) cache.computeIfAbsent("report-templates",
                k -> read(k, new TypeReference<List<ReportTemplate>>() { }).orElse(List.of()));
    }

    public Optional<ReportTemplate> reportTemplate(String id) {
        return reportTemplates().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public Settings.AiConfig ai() {
        return (Settings.AiConfig) cache.computeIfAbsent("ai", k -> read(k, new TypeReference<Settings.AiConfig>() { })
                .orElse(new Settings.AiConfig("claude", null)));
    }

    // ───────── 쓰기 ─────────

    public void saveAi(Settings.AiConfig ai) {
        String engine = "codex".equals(ai.engine()) ? "codex" : "claude";
        write("ai", new Settings.AiConfig(engine, blankToNull(ai.codexModel())));
    }

    /** 목록 순서가 곧 순서. oldName 이 있으면 이름 바꾸기(할 일의 카테고리도 같이). 쓰는 할 일이 있는 카테고리는 지울 수 없다 */
    @Transactional
    public void saveCategories(List<Category> list) {
        Set<String> seen = new HashSet<>();
        for (Category c : list) {
            String name = c.name() == null ? "" : c.name().strip();
            if (name.isEmpty() || !seen.add(name)) {
                throw new IllegalArgumentException("카테고리 이름이 비었거나 겹칩니다: " + name);
            }
            if (c.color() != null && !c.color().isBlank() && !COLORS.contains(c.color())) {
                throw new IllegalArgumentException("모르는 색: " + c.color());
            }
            if (nonNull(c.subtypes()).size() > 12) {
                throw new IllegalArgumentException("'" + name + "' 세부 분류는 12개까지");
            }
        }
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Category c = list.get(i);
            String name = c.name().strip();
            String old = c.oldName() == null ? null : c.oldName().strip();
            if (old != null && !old.isEmpty() && !old.equals(name)) {
                jdbc.sql("UPDATE category SET name = ? WHERE name = ?").params(name, old).update();
                jdbc.sql("UPDATE work_item SET category = ? WHERE category = ?").params(name, old).update();
                renameProjectCategory(old, name);
            }
            if (c.subtypes() == null) {
                // 세부 분류를 모르는 요청(예전 화면·설치 안내)은 있던 세부 분류를 그대로 둔다
                jdbc.sql("""
                        INSERT INTO category (name, sort, color) VALUES (?, ?, ?)
                        ON CONFLICT(name) DO UPDATE SET sort = excluded.sort, color = excluded.color
                        """).params(name, i + 1, blankToNull(c.color())).update();
                keep.add(name);
                continue;
            }
            List<String> subs = new ArrayList<>(new LinkedHashSet<>(nonNull(c.subtypes())));
            jdbc.sql("""
                    INSERT INTO category (name, sort, color, subtypes) VALUES (?, ?, ?, ?)
                    ON CONFLICT(name) DO UPDATE SET sort = excluded.sort, color = excluded.color, subtypes = excluded.subtypes
                    """).params(name, i + 1, blankToNull(c.color()), subs.isEmpty() ? null : json(subs)).update();
            // 목록에서 빠진 세부 분류를 쓰던 할 일은 비운다
            if (subs.isEmpty()) {
                jdbc.sql("UPDATE work_item SET subtype = NULL WHERE category = ? AND subtype IS NOT NULL").param(name).update();
            } else {
                jdbc.sql("UPDATE work_item SET subtype = NULL WHERE category = :name AND subtype IS NOT NULL AND subtype NOT IN (:subs)")
                        .param("name", name).param("subs", subs).update();
            }
            keep.add(name);
        }
        for (Category existing : categories()) {
            if (keep.contains(existing.name())) {
                continue;
            }
            Integer used = jdbc.sql("SELECT COUNT(*) FROM work_item WHERE category = ?").param(existing.name()).query(Integer.class).single();
            if (used > 0) {
                throw new IllegalStateException("'" + existing.name() + "' 은 할 일 " + used + "개가 쓰고 있어 지울 수 없습니다 — 먼저 이름을 바꾸거나 할 일을 옮기세요");
            }
            jdbc.sql("DELETE FROM category WHERE name = ?").param(existing.name()).update();
        }
    }

    public void saveProjects(List<ProjectRule> list) {
        Map<String, ProjectRule> byRepo = new LinkedHashMap<>();
        for (ProjectRule r : list) {
            String repo = r.repo() == null ? "" : r.repo().strip();
            String cat = r.category() == null ? "" : r.category().strip();
            if (repo.isEmpty() || cat.isEmpty()) {
                throw new IllegalArgumentException("저장소와 카테고리를 모두 적으세요");
            }
            byRepo.put(repo, new ProjectRule(repo, cat));
        }
        write("projects", List.copyOf(byRepo.values()));
    }

    public void saveRoutines(List<RoutineRule> list) {
        List<RoutineRule> clean = new ArrayList<>();
        for (RoutineRule r : list) {
            String title = r.title() == null ? "" : r.title().strip();
            if (title.isEmpty()) {
                throw new IllegalArgumentException("세션 제목을 적으세요");
            }
            String label = r.label() == null || r.label().isBlank() ? title : r.label().strip();
            String color = blankToNull(r.color());
            if (color != null && !COLORS.contains(color)) {
                throw new IllegalArgumentException("모르는 색: " + color);
            }
            clean.add(new RoutineRule(title, label, color));
        }
        write("routines", clean);
    }

    public void saveWorkText(WorkTextConfig config) {
        WorkTextPart full = config.full() == null ? DEFAULT_FULL : config.full();
        full = new WorkTextPart("full", blankOr(full.title(), DEFAULT_FULL.title()), "items", List.of(), List.of(), List.of(), null,
                blankOr(full.guide(), DEFAULT_FULL.guide()), blankOr(full.example(), DEFAULT_FULL.example()));
        Set<String> ids = new HashSet<>();
        List<WorkTextPart> parts = new ArrayList<>();
        for (WorkTextPart p : config.parts() == null ? List.<WorkTextPart>of() : config.parts()) {
            String id = p.id() == null ? "" : p.id().strip();
            if (!PART_ID.matcher(id).matches() || "full".equals(id) || !ids.add(id)) {
                throw new IllegalArgumentException("갈래 id 는 영문 소문자·숫자·- 로, 겹치지 않게: " + id);
            }
            if (p.title() == null || p.title().isBlank()) {
                throw new IllegalArgumentException("갈래 이름을 적으세요");
            }
            String source = "commits".equals(p.source()) || "next".equals(p.source()) ? p.source() : "items";
            parts.add(new WorkTextPart(id, p.title().strip(), source, nonNull(p.categories()), nonNull(p.keywords()),
                    nonNull(p.repos()), p.maxLines() == null || p.maxLines() < 1 ? 3 : Math.min(p.maxLines(), 12),
                    blankToNull(p.guide()), blankToNull(p.example())));
        }
        write("work-text", new WorkTextConfig(full, parts));
    }

    /** 목록 순서가 곧 리포트 화면 버튼 순서 */
    public void saveReportTemplates(List<ReportTemplate> list) {
        Set<String> ids = new HashSet<>();
        List<ReportTemplate> clean = new ArrayList<>();
        for (ReportTemplate t : list) {
            String id = t.id() == null ? "" : t.id().strip();
            if (!PART_ID.matcher(id).matches() || "default".equals(id) || !ids.add(id)) {
                throw new IllegalArgumentException("양식 id 는 영문 소문자·숫자·- 로, 겹치지 않게: " + id);
            }
            if (t.title() == null || t.title().isBlank()) {
                throw new IllegalArgumentException("양식 제목을 적으세요");
            }
            if (t.format() == null || t.format().isBlank()) {
                throw new IllegalArgumentException("'" + t.title().strip() + "' 양식 틀이 비었습니다");
            }
            String kind = "month".equals(t.kind()) ? "month" : "week";
            clean.add(new ReportTemplate(id, t.title().strip(), kind, t.format().stripTrailing(), blankToNull(t.guide()),
                    nonNull(t.categories())));
        }
        write("report-templates", clean);
    }

    // ───────── 내부 ─────────

    private void renameProjectCategory(String old, String name) {
        List<ProjectRule> next = projects().stream()
                .map(r -> r.category().equals(old) ? new ProjectRule(r.repo(), name) : r).toList();
        write("projects", next);
    }

    private <T> Optional<T> read(String key, TypeReference<T> type) {
        return jdbc.sql("SELECT value FROM setting WHERE key = ?").param(key).query(String.class).optional().map(v -> {
            try {
                return MAPPER.readValue(v, type);
            } catch (Exception e) {
                throw new IllegalStateException("설정 " + key + " 을 읽지 못함: " + e.getMessage(), e);
            }
        });
    }

    private void write(String key, Object value) {
        try {
            jdbc.sql("""
                    INSERT INTO setting (key, value, updated_at) VALUES (?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                    """).params(key, MAPPER.writeValueAsString(value), Instant.now().toString()).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        cache.remove(key);
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> nonNull(List<String> l) {
        return l == null ? List.of() : l.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String blankOr(String s, String dflt) {
        return s == null || s.isBlank() ? dflt : s;
    }
}
