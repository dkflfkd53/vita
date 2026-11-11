package com.worklog.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.worklog.agent.ObserverService;
import com.worklog.agent.RuleLoadService;
import com.worklog.agent.ObserverViews.DietItem;
import com.worklog.agent.ObserverViews.DietLog;
import com.worklog.agent.ObserverViews.RuleLoad;
import com.worklog.agent.ObserverViews.Insight;
import com.worklog.agent.ObserverViews.InsightRequest;
import com.worklog.agent.ObserverViews.MetricDef;
import com.worklog.agent.ObserverViews.Observer;
import com.worklog.agent.ObserverViews.Outcomes;
import com.worklog.agent.ObserverViews.Pending;
import com.worklog.agent.ObserverViews.Target;
import com.worklog.summary.JobService.JobView;

import lombok.RequiredArgsConstructor;

/** 에이전트 옵저버 — 지표·병목, 개선 제안 도입·효과 측정 */
@RestController
@RequestMapping("/api/observer")
@RequiredArgsConstructor
public class ObserverController {

    private final ObserverService service;
    private final RuleLoadService ruleLoad;

    /** kind = week | month | year */
    @GetMapping("/{kind:week|month|year}/{key}")
    public Observer period(@PathVariable String kind, @PathVariable String key, @RequestParam(required = false) String scope) {
        return service.observer(kind, key, scope);
    }

    @GetMapping("/weeks/{week}")
    public Observer week(@PathVariable String week, @RequestParam(required = false) String scope) {
        return service.observer(week, scope);
    }

    /** 아직 개선점을 안 본 세션 — 새 세션과, 봤던 뒤로 내용이 이어진 세션 */
    @GetMapping("/insights/pending")
    public Pending pending() {
        return service.pending();
    }

    /** 새로 볼 세션 전부에서 개선점 찾기 */
    @PostMapping("/insights/scan")
    public JobView scan() {
        return service.submitScan().view();
    }

    /** 세션 하나(작업 묶음)를 처음부터 다시 보고 개선점 찾기 */
    @PostMapping("/insights/scan/{taskKey}")
    public JobView scanTask(@PathVariable String taskKey) {
        return service.submitScan(taskKey).view();
    }

    @GetMapping("/metric-defs")
    public List<MetricDef> metricDefs() {
        return ObserverService.metricDefs();
    }

    /** 넣을 곳마다 매 세션 읽히는 양·Vita 규칙 수(예산) */
    @GetMapping("/rule-load")
    public List<RuleLoad> ruleLoad() {
        return ruleLoad.load();
    }

    /** 메모리 색인 줄이기 — 지금 남은 제안 */
    @GetMapping("/diet")
    public List<DietItem> diet(@RequestParam String target) {
        return ruleLoad.diet(target);
    }

    @GetMapping("/diet/log")
    public List<DietLog> dietLog(@RequestParam String target) {
        return ruleLoad.dietLog(target);
    }

    @PostMapping("/diet")
    public JobView dietPropose(@RequestParam String target) {
        return ruleLoad.submitDiet(target).view();
    }

    /** 고른 줄만 적용(쓰기 전 백업) */
    @PostMapping("/diet/apply")
    public Map<String, String> dietApply(@RequestParam String target, @RequestBody List<Long> ids) throws IOException {
        return Map.of("message", ruleLoad.applyDiet(target, ids));
    }

    @PostMapping("/diet/{id}/dismiss")
    public ResponseEntity<Void> dietDismiss(@PathVariable long id) {
        ruleLoad.dismissDiet(id);
        return ResponseEntity.noContent().build();
    }

    /** 도입한 규칙의 효과 — 같은 실수 재발(주별·도입 전후)과 지표 전후 */
    @GetMapping("/outcomes")
    public Outcomes outcomes() {
        return service.outcomes();
    }

    /** 재발 신호가 빠진 제안에 AI 로 신호 붙이기 */
    @PostMapping("/insights/signals")
    public JobView signals() {
        return service.submitSignals().view();
    }

    @GetMapping("/targets")
    public List<Target> targets() {
        return service.targets();
    }

    @GetMapping("/insights")
    public List<Insight> insights() {
        return service.insights();
    }

    @PostMapping("/insights")
    public Map<String, Long> create(@RequestBody InsightRequest req, @RequestParam(required = false) String week) {
        return Map.of("id", service.create(req, "manual", week));
    }

    @PatchMapping("/insights/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody InsightRequest req) {
        service.update(id, req);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/insights/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** write=true 면 고른 md 에 문안을 실제로 덧붙인다. false 면 "직접 넣었음"으로 기록만 */
    /** 필수 단계 제안을 한꺼번에 도입 */
    @PostMapping("/insights/adopt-must")
    public Map<String, Integer> adoptMust(@RequestParam(defaultValue = "true") boolean write) throws IOException {
        return Map.of("adopted", service.adoptMust(write));
    }

    /** 발생 세션이 빈 예전 제안에 근거 문장 속 세션 제목으로 세션을 붙인다 */
    @PostMapping("/insights/link-sessions")
    public Map<String, Integer> linkSessions() {
        return Map.of("linked", service.linkSessions());
    }

    /** 묶여 있는 제안을 규칙 하나씩으로 나누고 단계 다시 매기기 */
    @PostMapping("/insights/reclassify")
    public JobView reclassify() {
        return service.submitReclassify().view();
    }

    @PostMapping("/insights/{id}/adopt")
    public Insight adopt(@PathVariable long id, @RequestParam(defaultValue = "true") boolean write) throws IOException {
        return service.adopt(id, write);
    }

    @PostMapping("/insights/{id}/write")
    public ResponseEntity<Void> write(@PathVariable long id) throws IOException {
        service.writeToFile(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/insights/{id}/revert")
    public ResponseEntity<Void> revert(@PathVariable long id) throws IOException {
        service.revert(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/insights/{id}/close")
    public ResponseEntity<Void> close(@PathVariable long id) {
        service.close(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/insights/{id}/reopen")
    public ResponseEntity<Void> reopen(@PathVariable long id) {
        service.reopen(id);
        return ResponseEntity.noContent().build();
    }
}
