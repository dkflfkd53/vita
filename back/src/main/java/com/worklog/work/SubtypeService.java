package com.worklog.work;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.worklog.config.SettingsService;
import com.worklog.config.WorklogProperties;
import com.worklog.summary.ClaudeCli;
import com.worklog.summary.JobService;
import com.worklog.summary.SummaryService;
import com.worklog.work.WorkStore.WorkItem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 할 일의 세부 분류(설정의 카테고리 세부 분류, 예: 프로젝트 A = 기능개발·오류·유지보수…)를 AI 로 채운다.
 * 비어 있는 것만 채우고, 사용자가 고른 분류는 건드리지 않는다. 근거 = 할 일 이름·메모
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubtypeService {

    private static final int BATCH = 40;
    private static final int MEMO_CHARS = 240;

    private final WorkStore store;
    private final SettingsService settings;
    private final ClaudeCli claude;
    private final JobService jobs;
    private final WorklogProperties props;

    /** 세부 분류가 빈 할 일 전부 */
    public JobService.Job submitFill() {
        return jobs.submit("subtype:fill", "세부 분류 채우기", progress -> {
            int n = fill(store.allItems());
            return n + "개 채움";
        });
    }

    /** @return 채운 개수 */
    public int fill(List<WorkItem> items) throws IOException {
        Map<String, List<String>> subtypes = settings.subtypes();
        List<WorkItem> targets = items.stream()
                .filter(w -> w.subtype() == null && w.category() != null && subtypes.containsKey(w.category()))
                .toList();
        int saved = 0;
        for (int i = 0; i < targets.size(); i += BATCH) {
            saved += fillBatch(targets.subList(i, Math.min(targets.size(), i + BATCH)), subtypes);
        }
        if (saved > 0) {
            log.info("세부 분류 {}개 채움 (대상 {})", saved, targets.size());
        }
        return saved;
    }

    private int fillBatch(List<WorkItem> batch, Map<String, List<String>> subtypes) throws IOException {
        JsonNode json = SummaryService.parseJson(claude.ask(prompt(batch, subtypes), props.classifyModel()));
        Map<Long, WorkItem> byId = new HashMap<>();
        batch.forEach(w -> byId.put(w.id(), w));
        int saved = 0;
        for (JsonNode n : json.path("items")) {
            WorkItem w = byId.get(n.path("id").asLong(-1));
            String s = n.path("subtype").asText(null);
            if (w != null && s != null && subtypes.get(w.category()).contains(s)) {
                store.setSubtype(w.id(), s);
                saved++;
            }
        }
        return saved;
    }

    static String prompt(List<WorkItem> batch, Map<String, List<String>> subtypes) {
        StringBuilder sb = new StringBuilder("[세부 분류]\n");
        subtypes.forEach((cat, list) -> sb.append("- ").append(cat).append(": ").append(String.join(" / ", list)).append('\n'));
        sb.append("\n[할 일]\n");
        for (WorkItem w : batch) {
            sb.append("- id ").append(w.id()).append(" | ").append(w.category()).append(" | ").append(w.title());
            if (w.memo() != null && !w.memo().isBlank()) {
                String memo = w.memo().replaceAll("\\s+", " ").strip();
                sb.append(" | 메모: ").append(memo.length() > MEMO_CHARS ? memo.substring(0, MEMO_CHARS) + "…" : memo);
            }
            sb.append('\n');
        }
        return """
                너는 개발자의 할 일을 세부 분류로 나누는 담당이다. [할 일] 하나하나를 그 카테고리의 [세부 분류] 중 하나로 고른다.
                - 할 일 이름과 메모로 판단한다. 이름이 그 분류 이름을 그대로 담고 있지 않아도 하는 일의 성격으로 고른다
                  (예: 화면·기능을 새로 만들거나 바꿈 = 기능개발류, 잘못 들어간 데이터를 SQL 로 고침 = 보정류, 장애·버그 수정 = 오류류,
                  배포·환경·백업·문서·점검 = 유지보수류, 장비 연동은 장비 이름이 든 분류).
                - 정말 어느 것도 아니면 null.
                - subtype 은 [세부 분류]에 적힌 이름을 글자 그대로.
                출력은 JSON 하나만, 코드펜스나 설명 없이:
                {"items": [{"id": <할 일 id>, "subtype": "<세부 분류 이름 또는 null>"}]}

                %s""".formatted(sb);
    }
}
