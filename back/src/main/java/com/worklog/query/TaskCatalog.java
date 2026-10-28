package com.worklog.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.worklog.query.Rows.SessionRow;

import lombok.RequiredArgsConstructor;

/**
 * 세션을 "작업" 단위로 묶는다. 컨텍스트가 넘쳐 이어진 세션(continued-in)과 제목이 같은 세션은 같은 작업이다.
 * 화면에서 이름을 바꿔 제목을 맞추면 그게 곧 수동 병합이다.
 */
@Component
@RequiredArgsConstructor
public class TaskCatalog {

    private static final String UNTITLED = "(제목 없음)";

    private final QueryStore store;

    public Index build() {
        List<SessionRow> sessions = store.sessions().stream().filter(s -> !s.hidden()).toList();
        Map<String, SessionRow> byId = new HashMap<>();
        sessions.forEach(s -> byId.put(s.id(), s));

        Map<String, String> parent = new HashMap<>();
        sessions.forEach(s -> parent.put(s.id(), s.id()));
        Map<String, String> firstByTitle = new HashMap<>();
        for (SessionRow s : sessions) {
            if (s.continuedIn() != null && byId.containsKey(s.continuedIn())) {
                union(parent, s.id(), s.continuedIn());
            }
            String title = s.displayTitle();
            if (!UNTITLED.equals(title)) {
                String norm = title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                String other = firstByTitle.putIfAbsent(norm, s.id());
                if (other != null) {
                    union(parent, s.id(), other);
                }
            }
        }

        Map<String, List<SessionRow>> groups = new LinkedHashMap<>();
        sessions.stream()
                .sorted(Comparator.comparing(SessionRow::firstAt))
                .forEach(s -> groups.computeIfAbsent(find(parent, s.id()), k -> new ArrayList<>()).add(s));

        Map<String, Task> tasks = new LinkedHashMap<>();
        Map<String, String> taskOfSession = new HashMap<>();
        for (List<SessionRow> group : groups.values()) {
            // 키 = 가장 먼저 시작한 세션 id(바뀌지 않음), 제목 = 가장 최근 세션 제목
            String key = group.getFirst().id();
            SessionRow latest = group.stream().max(Comparator.comparing(SessionRow::lastAt)).orElseThrow();
            LinkedHashSet<String> projects = new LinkedHashSet<>();
            group.stream().sorted(Comparator.comparing(SessionRow::lastAt).reversed())
                    .map(SessionRow::project).filter(p -> p != null && !p.isBlank()).forEach(projects::add);
            tasks.put(key, new Task(key, latest.displayTitle(), List.copyOf(projects), List.copyOf(group)));
            group.forEach(s -> taskOfSession.put(s.id(), key));
        }
        return new Index(tasks, taskOfSession);
    }

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        parent.put(x, root);
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
        }
    }

    public record Task(String key, String title, List<String> projects, List<SessionRow> sessions) {

        public String project() {
            return projects.isEmpty() ? null : projects.getFirst();
        }
    }

    public record Index(Map<String, Task> tasks, Map<String, String> taskOfSession) {

        public Task taskOf(String sessionId) {
            String key = taskOfSession.get(sessionId);
            return key == null ? null : tasks.get(key);
        }
    }
}
