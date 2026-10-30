package com.worklog.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.worklog.report.Period;
import com.worklog.report.WorkTextService;
import com.worklog.summary.JobService.JobView;
import com.worklog.work.ClassifyService;
import com.worklog.work.SubtypeService;
import com.worklog.work.WorkService;
import com.worklog.work.WorkViews;
import com.worklog.work.WorkViews.Board;
import com.worklog.work.WorkViews.FromSessionRequest;
import com.worklog.work.WorkViews.ItemDetail;
import com.worklog.work.WorkViews.ItemSummary;
import com.worklog.work.WorkViews.LinkRequest;
import com.worklog.work.WorkViews.SessionCandidate;
import com.worklog.work.WorkViews.WorkItemRequest;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorkController {

    private final WorkService work;
    private final ClassifyService classify;
    private final WorkTextService workText;
    private final SubtypeService subtypes;

    @GetMapping("/weeks/{week}/board")
    public Board board(@PathVariable String week) {
        return work.board(week);
    }

    @GetMapping("/weeks/{week}/work-text")
    public WorkTextService.WorkText workText(@PathVariable String week) {
        return workText.get(week);
    }

    /** part = full 또는 설정의 갈래 id, body: {"content": "..."} — 빈 글이면 저장본을 지워 초안으로 */
    @PutMapping("/weeks/{week}/work-text/{part}")
    public ResponseEntity<Void> saveWorkText(@PathVariable String week, @PathVariable String part,
                                             @RequestBody Map<String, String> body) {
        workText.save(week, part, body.get("content"));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/weeks/{week}/work-text/{part}")
    public ResponseEntity<Void> resetWorkText(@PathVariable String week, @PathVariable String part) {
        workText.reset(week, part);
        return ResponseEntity.noContent().build();
    }

    /** part 없으면(all) 모든 갈래를 한 번에 — 직접 고친 갈래는 그대로 둔다 */
    @PostMapping("/weeks/{week}/work-text/ai")
    public JobView workTextAi(@PathVariable String week, @RequestParam(defaultValue = "all") String part) {
        return workText.submitAi(week, "all".equals(part) ? null : part).view();
    }

    @GetMapping("/months/{month}/board")
    public Board monthBoard(@PathVariable String month) {
        return work.monthBoard(month);
    }

    /** redo=true 면 AI 가 이미 붙인 것도 다시 판단 */
    @PostMapping("/weeks/{week}/classify")
    public JobView classify(@PathVariable String week, @RequestParam(defaultValue = "false") boolean redo) {
        return classify.submit(week, redo).view();
    }

    @PostMapping("/weeks/{week}/items/{id}")
    public ResponseEntity<Void> plan(@PathVariable String week, @PathVariable long id) {
        work.plan(week, id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/weeks/{week}/items/{id}")
    public ResponseEntity<Void> unplan(@PathVariable String week, @PathVariable long id) {
        work.unplan(week, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/work-items")
    public List<ItemSummary> items() {
        return work.itemSummaries();
    }

    /** 세션 목록 할 일별 묶기용 — 작업(세션 묶음)마다 가장 최근에 연결된 할 일 */
    @GetMapping("/task-work")
    public List<WorkViews.TaskWork> taskWork() {
        return work.taskWork();
    }

    @PostMapping("/work-items")
    public Map<String, Long> create(@RequestBody WorkItemRequest req) {
        return Map.of("id", work.create(req));
    }

    @GetMapping("/work-items/{id}")
    public ItemDetail detail(@PathVariable long id) {
        return work.detail(id);
    }

    @PatchMapping("/work-items/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody WorkItemRequest req) {
        work.update(id, req);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/work-items/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        work.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 세부 분류가 빈 할 일을 AI 로 채운다(설정의 카테고리 세부 분류 기준) */
    @PostMapping("/work-items/subtypes/fill")
    public JobView fillSubtypes() {
        return subtypes.submitFill().view();
    }

    @PostMapping("/work-items/from-session")
    public Map<String, Long> fromSession(@RequestBody FromSessionRequest req) {
        return Map.of("id", work.fromSession(req));
    }

    /** 할 일에 세션 직접 연결 — 이번 주 작업 + 앞으로의 작업 */
    @PostMapping("/work-items/{id}/sessions/{sessionId}")
    public ResponseEntity<Void> attachSession(@PathVariable long id, @PathVariable String sessionId,
                                              @RequestParam(required = false) String week,
                                              @RequestParam(required = false) String month) {
        work.attachSession(id, sessionId, period(week, month));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/work-items/{id}/sessions/{sessionId}")
    public ResponseEntity<Void> detachSession(@PathVariable long id, @PathVariable String sessionId) {
        work.detachSession(id, sessionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/weeks/{week}/session-candidates")
    public List<SessionCandidate> candidates(@PathVariable String week) {
        return work.candidates(Period.of("week", week));
    }

    @GetMapping("/months/{month}/session-candidates")
    public List<SessionCandidate> monthCandidates(@PathVariable String month) {
        return work.candidates(Period.of("month", month));
    }

    private static Period period(String week, String month) {
        if (week != null) {
            return Period.of("week", week);
        }
        if (month != null) {
            return Period.of("month", month);
        }
        throw new IllegalArgumentException("week 또는 month 가 필요함");
    }

    @PutMapping("/work-links")
    public ResponseEntity<Void> link(@RequestBody LinkRequest req) {
        work.link(req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return work.categories();
    }

    @PostMapping("/categories")
    public ResponseEntity<Void> addCategory(@RequestBody Map<String, String> body) {
        work.addCategory(body.get("name"));
        return ResponseEntity.noContent().build();
    }
}
