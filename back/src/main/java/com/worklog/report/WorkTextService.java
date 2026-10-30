package com.worklog.report;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.worklog.collect.CollectScheduler;
import com.worklog.config.ProjectScope;
import com.worklog.config.Settings.WorkTextConfig;
import com.worklog.config.Settings.WorkTextPart;
import com.worklog.config.SettingsService;
import com.worklog.query.QueryStore;
import com.worklog.query.Rows.CommitRow;
import com.worklog.query.Rows.EditRow;
import com.worklog.query.Rows.ReportRow;
import com.worklog.summary.ClaudeCli;
import com.worklog.summary.JobService;
import com.worklog.summary.SummaryService;
import com.worklog.work.WorkService;
import com.worklog.work.WorkViews.Board;
import com.worklog.work.WorkViews.ItemView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 주간 업무 내용 — 사내 주간보고에 붙이는 글을 갈래별로 따로 만든다. 갈래는 설정에서 정한다.
 * <ul>
 *   <li>full(늘 있음): 이번 주 한 일 전체 — 카테고리별 할 일과 세부</li>
 *   <li>사용자 갈래: items(할 일을 카테고리·키워드로 거름) 또는 commits(저장소 커밋) 기준의 짧은 요약,
 *       next(차주 계획 — 다음 주 보드의 할 일 + 이번 주에 못 끝낸 할 일 + 다음 주 마감)</li>
 * </ul>
 * 초안은 할 일·메모·커밋으로 바로 만들고, AI 는 갈래의 지시(guide)·문체 예시(example)대로 다시 쓴다. 사용자가 고친 글
 * (model=manual)은 그대로 두고 다음 주 AI 작성의 문체 예시로 쓴다. 저장 = report(kind=worktext | worktext-갈래id, period_key=주 키)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkTextService {

    private static final String I1 = "    ";
    private static final String I2 = "        ";
    /** 세부 분류가 있는 카테고리에서 분류를 안 고른 할 일 */
    static final String UNSORTED = "미분류";
    private static final Pattern GITMOJI = Pattern.compile("^\\S*\\s*(\\w+)(\\([^)]*\\))?!?:\\s*");
    /** 메모의 주 표시 — "10월 3주차"(목요일 기준, 화면 표기) 또는 ISO 주 번호 "42주" */
    private static final String WEEK_TAG = "(?:(\\d{1,2})월\\s*(\\d)주차|(\\d{1,2})주(?!차))";
    private static final Pattern WEEK_HEADER = Pattern.compile("^\\s*" + WEEK_TAG);
    private static final Pattern WEEK_PREFIX = Pattern.compile("^" + WEEK_TAG + ":\\s*");

    private final WorkService work;
    private final QueryStore store;
    private final ClaudeCli claude;
    private final JobService jobs;
    private final CollectScheduler collector;
    private final SettingsService settings;
    private final ProjectScope scope;

    public record PartText(String part, String title, String content, String source, String generatedAt) {
    }

    public record WorkText(String week, String label, List<PartText> parts) {
    }

    /** 갈래 — 설정 값 + 저장 kind */
    record Part(WorkTextPart def) {
        String id() {
            return def.id();
        }

        String title() {
            return def.title();
        }

        String kind() {
            return "full".equals(def.id()) ? "worktext" : "worktext-" + def.id();
        }

        boolean full() {
            return "full".equals(def.id());
        }

        boolean next() {
            return "next".equals(def.source());
        }

        String mark() {
            return "<<" + def.title() + ">>";
        }
    }

    private List<Part> parts() {
        WorkTextConfig c = settings.workText();
        List<Part> list = new ArrayList<>();
        list.add(new Part(c.full()));
        c.parts().forEach(p -> list.add(new Part(p)));
        return list;
    }

    public Part part(String id) {
        return parts().stream().filter(p -> p.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("없는 갈래: " + id));
    }

    /** 모든 갈래 — 저장본이 있으면 그것, 없으면 초안. source = draft | ai | manual */
    public WorkText get(String weekKey) {
        Period p = Period.of("week", weekKey);
        Board board = work.board(weekKey);
        List<PartText> out = new ArrayList<>();
        for (Part part : parts()) {
            out.add(store.report(part.kind(), weekKey)
                    .map(r -> new PartText(part.id(), part.title(), r.content(), "manual".equals(r.model()) ? "manual" : "ai",
                            r.generatedAt()))
                    .orElseGet(() -> new PartText(part.id(), part.title(), draft(part, board, p), "draft", null)));
        }
        return new WorkText(weekKey, p.label(), out);
    }

    public void save(String weekKey, String partId, String content) {
        Period.of("week", weekKey);
        Part part = part(partId);
        if (content == null || content.isBlank()) {
            store.deleteReport(part.kind(), weekKey);
            return;
        }
        store.saveReport(part.kind(), weekKey, content.stripTrailing(), "manual");
    }

    public void reset(String weekKey, String partId) {
        store.deleteReport(part(partId).kind(), weekKey);
    }

    /** partId = null 이면 모든 갈래를 한 번에(직접 고친 갈래는 건드리지 않음), 아니면 그 갈래만 덮어쓴다 */
    public JobService.Job submitAi(String weekKey, String partId) {
        Period p = Period.of("week", weekKey);
        Part one = partId == null ? null : part(partId);
        String what = one == null ? "업무 내용 전체" : one.title();
        return jobs.submit("worktext:" + weekKey + ":" + (one == null ? "all" : one.id()), p.label() + " " + what, progress -> {
            collector.collectAndWait();
            progress.update(what + " 작성 중");
            String input = input(weekKey);
            if (one != null) {
                String text = clean(claude.ask(prompt(p, input, List.of(one))), one);
                store.saveReport(one.kind(), weekKey, text, claude.model());
                return p.label() + " " + one.title() + " 완료";
            }
            List<Part> targets = new ArrayList<>();
            for (Part pt : parts()) {
                boolean manual = store.report(pt.kind(), weekKey).map(r -> "manual".equals(r.model())).orElse(false);
                if (!manual) {
                    targets.add(pt);
                }
            }
            if (targets.isEmpty()) {
                return "모두 직접 고친 글이라 건너뜀";
            }
            Map<Part, String> out = split(claude.ask(prompt(p, input, targets)), targets);
            out.forEach((pt, text) -> store.saveReport(pt.kind(), weekKey, text, claude.model()));
            return p.label() + " 업무 내용 " + out.size() + "개 완료";
        });
    }

    // ───────── 초안(규칙) ─────────

    String draft(Part part, Board board, Period p) {
        if (part.full()) {
            return fullDraft(board);
        }
        if (part.next()) {
            return nextDraft(part, board);
        }
        return "commits".equals(part.def().source()) ? commitDraft(part, p) : itemDraft(part, board);
    }

    private String fullDraft(Board board) {
        StringBuilder sb = new StringBuilder("[업무 내용]\n\n");
        Map<String, List<String>> subtypes = settings.subtypes();
        for (Map.Entry<String, List<ItemView>> g : groups(board).entrySet()) {
            if (g.getValue().isEmpty()) {
                continue;
            }
            sb.append("- ").append(g.getKey()).append('\n');
            // 세부 분류가 있는 카테고리는 분류 줄을 한 단계 끼운다
            for (Map.Entry<String, List<ItemView>> s : bySubtype(g.getValue(), subtypes.get(g.getKey())).entrySet()) {
                String pad = s.getKey() == null ? "" : I1;
                if (s.getKey() != null) {
                    sb.append(I1).append("- ").append(s.getKey()).append('\n');
                }
                for (ItemView i : s.getValue()) {
                    sb.append(pad).append(I1).append("- ").append(i.title()).append('\n');
                    for (String b : memoBullets(i.memo(), board.key())) {
                        sb.append(pad).append(I2).append("- ").append(b).append('\n');
                    }
                }
            }
        }
        return sb.toString().stripTrailing() + "\n";
    }

    /**
     * 카테고리 안 할 일을 세부 분류 순서대로 묶는다. order 가 없으면(세부 분류 없는 카테고리) 키 null 하나에 전부.
     * 분류가 빈 할 일은 맨 끝 "미분류"
     */
    static Map<String, List<ItemView>> bySubtype(List<ItemView> items, List<String> order) {
        Map<String, List<ItemView>> m = new LinkedHashMap<>();
        if (order == null || order.isEmpty()) {
            m.put(null, items);
            return m;
        }
        for (String s : order) {
            m.put(s, new ArrayList<>());
        }
        for (ItemView i : items) {
            String key = i.subtype() != null && order.contains(i.subtype()) ? i.subtype() : UNSORTED;
            m.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        m.values().removeIf(List::isEmpty);
        return m;
    }

    /** 차주 계획 초안 — 전체 업무 내용과 같은 모양으로 카테고리별 할 일 이름(마감·보드 표시) */
    private String nextDraft(Part part, Board board) {
        Map<String, List<ItemView>> groups = new LinkedHashMap<>();
        board.categories().forEach(c -> groups.put(c, new ArrayList<>()));
        for (ItemView i : nextItems(board)) {
            if (matches(part, i)) {
                groups.computeIfAbsent(i.category() == null ? "기타" : i.category(), k -> new ArrayList<>()).add(i);
            }
        }
        StringBuilder sb = new StringBuilder("[" + part.title() + "]\n\n");
        boolean any = false;
        for (Map.Entry<String, List<ItemView>> g : groups.entrySet()) {
            if (g.getValue().isEmpty()) {
                continue;
            }
            any = true;
            sb.append("- ").append(g.getKey()).append('\n');
            for (Map.Entry<String, List<ItemView>> s : bySubtype(g.getValue(), settings.subtypes().get(g.getKey())).entrySet()) {
                String pad = s.getKey() == null ? "" : I1;
                if (s.getKey() != null) {
                    sb.append(I1).append("- ").append(s.getKey()).append('\n');
                }
                for (ItemView i : s.getValue()) {
                    sb.append(pad).append(I1).append("- ").append(i.title())
                            .append(i.dueDate() == null ? "" : " (~" + mmdd(i.dueDate()) + ")").append('\n');
                }
            }
        }
        return any ? sb.toString() : sb.append("- (다음 주 할 일 없음 — 업무 화면에서 다음 주 할 일을 넣거나 이번 주 할 일 상태를 확인하세요)\n").toString();
    }

    /**
     * 차주 계획 후보 — 다음 주 보드에 넣은 할 일, 이번 주 할 일 중 완료·보류가 아닌 것(이번 주에 계획했거나 작업한 것),
     * 마감이 다음 주인 할 일. 카테고리 순
     */
    List<ItemView> nextItems(Board board) {
        LocalDate nextFrom = LocalDate.parse(board.from()).plusDays(7);
        Board next = work.board(Period.weekKey(nextFrom));
        Map<Long, ItemView> out = new LinkedHashMap<>();
        next.items().stream().filter(i -> i.planned() && !"DONE".equals(i.status())).forEach(i -> out.put(i.id(), i));
        for (ItemView i : board.items()) {
            boolean open = !"DONE".equals(i.status()) && !"ON_HOLD".equals(i.status());
            boolean dueNext = i.dueDate() != null && !LocalDate.parse(i.dueDate()).isBefore(nextFrom)
                    && !LocalDate.parse(i.dueDate()).isAfter(nextFrom.plusDays(6));
            if (open && (i.planned() || i.activeSec() > 0 || dueNext)) {
                out.putIfAbsent(i.id(), i);
            }
        }
        List<String> order = board.categories();
        return out.values().stream()
                .sorted(Comparator.comparingInt((ItemView i) -> {
                    int k = order.indexOf(i.category());
                    return k < 0 ? order.size() : k;
                }))
                .toList();
    }

    private static String mmdd(String date) {
        LocalDate d = LocalDate.parse(date);
        return d.getMonthValue() + "/" + d.getDayOfMonth();
    }

    /** 할 일 기준 갈래 — 걸러진 할 일의 이번 주 메모 줄(없으면 할 일 이름) */
    private String itemDraft(Part part, Board board) {
        List<String> lines = new ArrayList<>();
        for (ItemView i : included(board)) {
            if (matches(part, i)) {
                List<String> bullets = memoBullets(i.memo(), board.key());
                if (bullets.isEmpty()) {
                    lines.add(i.title());
                }
                lines.addAll(bullets);
            }
        }
        if (lines.isEmpty()) {
            return "- (이번 주 " + part.title() + " 해당 작업 없음)\n";
        }
        StringBuilder sb = new StringBuilder();
        lines.stream().limit(maxLines(part)).forEach(l -> sb.append("- ").append(l).append('\n'));
        return sb.toString();
    }

    /** 커밋 기준 갈래 — 고른 저장소(비면 관리하는 저장소 전부)의 이번 주 커밋 제목 */
    private String commitDraft(Part part, Period p) {
        Set<String> lines = new LinkedHashSet<>();
        List<String> repos = part.def().repos();
        store.rawCommits(p.from().toString(), p.to().toString()).stream()
                .filter(c -> repos.isEmpty() || repos.contains(c.repo()))
                .forEach(c -> lines.add(stripPrefix(c.subject())));
        if (lines.isEmpty()) {
            return "- (이번 주 커밋 없음)\n";
        }
        StringBuilder sb = new StringBuilder();
        lines.stream().limit(maxLines(part)).forEach(l -> sb.append("- ").append(l).append('\n'));
        return sb.toString();
    }

    private static int maxLines(Part part) {
        return part.def().maxLines() == null ? 3 : part.def().maxLines();
    }

    /** 할 일 기준 갈래에 드는 할 일인가 — 카테고리(비면 전부) 그리고 키워드(비면 전부, 제목·메모 중 하나라도) */
    static boolean matches(Part part, ItemView i) {
        List<String> cats = part.def().categories();
        if (!cats.isEmpty() && (i.category() == null || !cats.contains(i.category()))) {
            return false;
        }
        List<String> words = part.def().keywords();
        if (words.isEmpty()) {
            return true;
        }
        String hay = (i.title() + "\n" + (i.subtype() == null ? "" : i.subtype()) + "\n" + (i.memo() == null ? "" : i.memo()))
                .toLowerCase(Locale.ROOT);
        return words.stream().anyMatch(w -> hay.contains(w.toLowerCase(Locale.ROOT)));
    }

    /** 이번 주에 실제로 작업했거나 이번 주에 끝낸 할 일만, 카테고리 순 */
    private Map<String, List<ItemView>> groups(Board board) {
        Map<String, List<ItemView>> groups = new LinkedHashMap<>();
        for (String c : board.categories()) {
            groups.put(c, new ArrayList<>());
        }
        for (ItemView i : included(board)) {
            groups.computeIfAbsent(i.category() == null ? "기타" : i.category(), k -> new ArrayList<>()).add(i);
        }
        return groups;
    }

    private static List<ItemView> included(Board board) {
        return board.items().stream().filter(i -> {
            boolean doneThisWeek = "DONE".equals(i.status()) && i.doneAt() != null
                    && day(i.doneAt()).compareTo(board.from()) >= 0 && day(i.doneAt()).compareTo(board.to()) <= 0;
            return i.activeSec() > 0 || doneThisWeek;
        }).toList();
    }

    private static String day(String isoInstant) {
        return LocalDate.ofInstant(Instant.parse(isoInstant), ZoneId.of("Asia/Seoul")).toString();
    }

    private static int isoWeek(String weekKey) {
        return Integer.parseInt(weekKey.substring(weekKey.indexOf("-W") + 2));
    }

    /**
     * 메모의 "- " 줄. "10월 3주차 (…)" 같은 주 머리나 "- 10월 3주차: …" 같은 주 표시가 있으면 이번 주 것만
     * (예전 ISO 표기 "42주"도 읽는다). "이어짐"·"…주 계획" 같은 메타 줄과 "실적:" 접두어는 뺀다
     */
    static List<String> memoBullets(String memo, String weekKey) {
        if (memo == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        boolean hasHeaders = memo.lines().anyMatch(l -> !l.strip().startsWith("-") && WEEK_HEADER.matcher(l).find());
        boolean inThisWeek = !hasHeaders;
        for (String raw : memo.lines().toList()) {
            String line = raw.strip();
            Matcher m = WEEK_HEADER.matcher(line);
            if (!line.startsWith("-") && m.find()) {
                inThisWeek = isWeek(m, weekKey);
                continue;
            }
            if (!line.startsWith("-") || !inThisWeek) {
                continue;
            }
            String b = line.substring(1).strip().replaceFirst("^실적:\\s*", "");
            Matcher wp = WEEK_PREFIX.matcher(b);
            if (wp.find()) {
                if (!isWeek(wp, weekKey)) {
                    continue;
                }
                b = b.substring(wp.end());
            }
            if (b.isEmpty() || b.contains("이어짐") || b.matches(".*\\d+주(차)?\\s*(차주)?계획.*")) {
                continue;
            }
            out.add(b);
        }
        return out;
    }

    /** WEEK_TAG 에 맞은 주 표시가 이 주인가 */
    private static boolean isWeek(Matcher m, String weekKey) {
        if (m.group(1) != null) {
            LocalDate thu = Period.of("week", weekKey).from().plusDays(3);
            return Integer.parseInt(m.group(1)) == thu.getMonthValue()
                    && Integer.parseInt(m.group(2)) == (thu.getDayOfMonth() - 1) / 7 + 1;
        }
        return Integer.parseInt(m.group(3)) == isoWeek(weekKey);
    }

    static String stripPrefix(String subject) {
        return GITMOJI.matcher(subject).replaceFirst("").strip();
    }

    // ───────── AI ─────────

    /** 모든 갈래가 함께 쓰는 근거 — 할 일·메모·세션 요약·커밋·수정 파일 */
    String input(String weekKey) {
        Period p = Period.of("week", weekKey);
        Board board = work.board(weekKey);
        List<Part> itemParts = parts().stream().filter(pt -> !pt.full() && "items".equals(pt.def().source())).toList();
        StringBuilder in = new StringBuilder();
        in.append("기간: ").append(p.label()).append("\n\n");
        Map<String, List<String>> subtypes = settings.subtypes();
        if (!subtypes.isEmpty()) {
            in.append("[세부 분류] (이 카테고리는 카테고리 아래를 분류로 한 번 더 나눈다)\n");
            subtypes.forEach((c, list) -> in.append("- ").append(c).append(": ").append(String.join(" / ", list)).append('\n'));
            in.append('\n');
        }
        in.append("[이번 주 할 일]\n");
        for (Map.Entry<String, List<ItemView>> g : groups(board).entrySet()) {
            for (ItemView i : g.getValue()) {
                List<String> tags = itemParts.stream().filter(pt -> matches(pt, i)).map(Part::title).toList();
                in.append("- [").append(g.getKey()).append("] ").append(i.title())
                        .append(tags.isEmpty() ? "" : " (" + String.join(", ", tags) + " 해당)")
                        .append(subtypeTag(i, subtypes))
                        .append(" | 상태 ").append(statusKo(i.status()))
                        .append(" | 이번 주 작업 ").append(ReportService.hours(i.activeSec())).append("시간\n");
                if (i.memo() != null && !i.memo().isBlank()) {
                    in.append("  메모:\n");
                    i.memo().lines().forEach(l -> in.append("    ").append(l).append('\n'));
                }
                List<String> sums = i.sessionDays().stream().filter(d -> d.summary() != null)
                        .map(d -> d.day().substring(5) + " " + d.summary()).toList();
                if (!sums.isEmpty()) {
                    in.append("  이번 주 작업 기록(세션 요약):\n");
                    sums.forEach(s -> in.append("    - ").append(s).append('\n'));
                }
            }
        }
        if (parts().stream().anyMatch(Part::next)) {
            in.append("\n[차주 계획 후보] (다음 주 보드에 넣은 할 일 · 이번 주에 못 끝낸 할 일 · 다음 주 마감)\n");
            List<ItemView> next = nextItems(board);
            if (next.isEmpty()) {
                in.append("(없음)\n");
            }
            for (ItemView i : next) {
                List<String> tags = parts().stream().filter(pt -> pt.next() && matches(pt, i)).map(Part::title).toList();
                in.append("- [").append(i.category() == null ? "기타" : i.category()).append("] ").append(i.title())
                        .append(tags.isEmpty() ? "" : " (" + String.join(", ", tags) + " 해당)")
                        .append(subtypeTag(i, subtypes))
                        .append(" | 상태 ").append(statusKo(i.status()))
                        .append(" | 마감 ").append(i.dueDate() == null ? "-" : i.dueDate())
                        .append(i.planned() && board.items().stream().noneMatch(b -> b.id() == i.id()) ? " | 다음 주 보드에 넣음" : "")
                        .append('\n');
                if (i.memo() != null && !i.memo().isBlank()) {
                    in.append("  메모:\n");
                    i.memo().lines().forEach(l -> in.append("    ").append(l).append('\n'));
                }
                List<String> recent = i.sessionDays().stream().filter(d -> d.summary() != null)
                        .map(d -> d.day().substring(5) + " " + d.summary()).toList();
                recent.stream().skip(Math.max(0, recent.size() - 3))
                        .forEach(s -> in.append("  최근 작업: ").append(s).append('\n'));
            }
        }
        in.append("\n[코드 변경]\n");
        List<CommitRow> commits = store.rawCommits(p.from().toString(), p.to().toString());
        Set<String> repos = new LinkedHashSet<>(scope.repos());
        commits.forEach(c -> repos.add(c.repo()));
        if (repos.isEmpty()) {
            in.append("(없음)\n");
        }
        for (String repo : repos) {
            in.append(repo).append(" 커밋:\n");
            List<CommitRow> mine = commits.stream().filter(c -> repo.equals(c.repo())).limit(40).toList();
            if (mine.isEmpty()) {
                in.append("  (없음)\n");
            }
            mine.forEach(c -> in.append("  - ").append(c.day().substring(5)).append(' ').append(c.subject()).append('\n'));
            Set<String> files = new LinkedHashSet<>();
            for (EditRow e : store.editsInRepo(repo, p.from().toString(), p.to().toString())) {
                files.add(shortPath(e.path(), repo));
            }
            if (!files.isEmpty()) {
                in.append("  수정 파일(커밋 전 포함): ").append(String.join(", ", files.stream().limit(25).toList())).append('\n');
            }
        }
        return in.toString();
    }

    String prompt(Period p, String input, List<Part> parts) {
        boolean multi = parts.size() > 1;
        StringBuilder spec = new StringBuilder();
        for (Part part : parts) {
            spec.append(multi ? "\n" + part.mark() + " 로 시작하는 구역에 다음을 쓴다:\n" : "\n");
            spec.append('[').append(part.title()).append("] ").append(guide(part)).append('\n');
        }

        StringBuilder examples = new StringBuilder();
        for (Part part : parts) {
            if (part.def().example() != null) {
                examples.append("\n(").append(part.title()).append(" 예시)\n").append(part.def().example().stripTrailing()).append('\n');
            }
            for (ReportRow r : store.savedReports(part.kind(), p.key(), 2)) {
                examples.append("(사용자가 직접 고쳐 저장한 ").append(Period.of("week", r.periodKey()).label()).append(" ")
                        .append(part.title()).append(")\n").append(r.content()).append('\n');
            }
        }

        return """
                너는 개발자의 사내 주간보고 글을 쓰는 담당이다. 아래 입력만 근거로 %s 글을 쓴다.

                %s
                문체(모든 글 공통):
                - 보고서체 명사형 종결(~ 추가 및 배포, ~ 분석 및 개선안 도출, ~ 작성, ~ 예정). "~함/~했다/~요" 금지
                - 구어 표현은 업무 용어로 바꾼다(예: "느린 거 고침" → "조회 성능 개선")
                - 원인·결과·수치(시간, 건수, 진행률)는 살린다. 이번 주에 한 일만 쓴다(메모의 다른 주 내용은 쓰지 않는다).
                  단 차주 계획 갈래는 다음 주에 할 일만 쓴다(~ 예정, ~ 진행 등 계획 표현)
                - 입력에 [세부 분류]가 있으면(이 규칙이 갈래 지시·예시보다 우선): 그 카테고리는 카테고리 줄 아래에 분류 줄 "- 분류명"을
                  한 단계 끼우고, 그 아래 업무·세부를 한 단계씩 더 들여쓴다(공백 4칸 단위). 분류는 [세부 분류] 순서, 업무의 분류는 입력의
                  "분류 …" 표시 그대로, 표시가 없으면 "미분류". 해당 업무가 없는 분류 줄은 뺀다
                쓸 것:%s
                출력은 글만. 설명·코드펜스 없이.%s

                [문체·형식 예시]%s
                ---
                %s
                """.formatted(p.label(), SummaryService.RULES, spec,
                multi ? " 구역 표시(" + String.join(", ", parts.stream().map(Part::mark).toList()) + ")는 각 구역 첫 줄에 그대로 쓴다." : "",
                examples, input);
    }

    /** 갈래 지시 — 사용자가 적은 지시가 없으면 거름 조건으로 기본 지시를 만든다 */
    private String guide(Part part) {
        if (part.def().guide() != null) {
            return part.def().guide();
        }
        if (part.next()) {
            return "입력의 [차주 계획 후보] 중 \"(" + part.title() + " 해당)\" 표시가 있는 것으로 다음 주에 할 일. 첫 줄 \"[" + part.title()
                    + "]\", 빈 줄, 그다음 카테고리마다 \"- 카테고리\"(4칸 들여쓰기 업무 \"- 업무명 (마감 M/D 또는 진행률)\", 8칸 세부 0~2줄). "
                    + "세부는 메모·최근 작업에서 아직 남은 일, 이어서 할 일만 \"~ 예정\" 체로. 근거가 없으면 세부 없이 업무명만. 완료된 일·이번 주 실적은 쓰지 않는다";
        }
        if ("commits".equals(part.def().source())) {
            String repos = part.def().repos().isEmpty() ? "관리하는 저장소" : String.join(", ", part.def().repos());
            return "[코드 변경]의 " + repos + " 커밋·수정 파일로 이번 주 수정사항만 간략히. \"- 작업명 (핵심 내용)\" 줄 최대 "
                    + maxLines(part) + "개. 커밋 이모지·접두어(feat/fix) 금지. 없으면 \"- (이번 주 수정 없음)\"";
        }
        return "입력의 할 일 중 \"(" + part.title() + " 해당)\" 표시가 있는 것만으로 이번 주 작업을 간략히. 머리말 없이 \"- \" 줄 최대 "
                + maxLines(part) + "개, 한 줄에 관련된 일 한두 가지와 수치를 괄호로. 해당 작업이 없으면 \"- (이번 주 해당 작업 없음)\"";
    }

    /** 여러 갈래를 한 번에 받은 응답을 구역 표시로 나눈다 */
    static Map<Part, String> split(String text, List<Part> targets) throws IOException {
        Map<Part, String> out = new LinkedHashMap<>();
        for (Part part : targets) {
            int start = text.indexOf(part.mark());
            if (start < 0) {
                continue;
            }
            int end = text.length();
            for (Part other : targets) {
                int idx = text.indexOf(other.mark(), start + 1);
                if (other != part && idx > start && idx < end) {
                    end = idx;
                }
            }
            out.put(part, clean(text.substring(start + part.mark().length(), end), part));
        }
        if (out.isEmpty()) {
            throw new IOException("응답에서 구역을 찾지 못함");
        }
        return out;
    }

    private static String clean(String text, Part part) {
        String t = text.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```\\w*\\n", "").replaceFirst("\\n```$", "");
        }
        if (part.full() || part.next()) {
            // 머리 줄 앞에 붙는 "기간: …" 같은 군더더기는 뺀다
            int i = t.indexOf(part.full() ? "[업무 내용]" : "[" + part.title() + "]");
            t = i > 0 ? t.substring(i) : t;
        }
        return t.stripTrailing() + "\n";
    }

    private static String shortPath(String path, String repo) {
        int idx = path.lastIndexOf("/" + repo + "/");
        return idx >= 0 ? path.substring(idx + repo.length() + 2) : path;
    }

    private static String subtypeTag(ItemView i, Map<String, List<String>> subtypes) {
        if (i.category() == null || !subtypes.containsKey(i.category())) {
            return "";
        }
        return " | 분류 " + (i.subtype() == null ? UNSORTED : i.subtype());
    }

    private static String statusKo(String s) {
        return switch (s) {
            case "DONE" -> "완료";
            case "IN_PROGRESS" -> "진행 중";
            case "ON_HOLD" -> "보류";
            default -> "예정";
        };
    }
}
