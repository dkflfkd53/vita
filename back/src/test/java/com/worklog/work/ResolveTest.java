package com.worklog.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.worklog.query.Views.SessionDayRef;
import com.worklog.work.WorkService.Resolution;
import com.worklog.work.WorkStore.Link;

/** 작업 묶음 단위 연결 — 이어진 세션의 고정 공유, 끈 세션 마무리 요청의 이어받기 */
class ResolveTest {

    private final WorkService service = new WorkService(null, null, null, null, null, null, null, null);

    private static SessionDayRef ref(String sid, String day, String task) {
        return new SessionDayRef(sid, day, "제목", "앱", task, 60, 1, 0, null, day + "T01:00:00Z", day + "T02:00:00Z", 0);
    }

    private static String sourceOf(Resolution r, String sid, String day) {
        return r.byItem().values().stream().flatMap(List::stream)
                .filter(d -> d.sessionId().equals(sid) && d.day().equals(day)).map(d -> d.source()).findFirst().orElse("unassigned");
    }

    private static Long itemOf(Resolution r, String sid, String day) {
        return r.byItem().entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(d -> d.sessionId().equals(sid) && d.day().equals(day)))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    @Test
    void continuedSessionSharesPinWithPredecessor() {
        // Worklog(old) → Vita(new) 로 이어짐, 고정은 new 에만
        Resolution r = service.resolve(List.of(ref("old", "2026-10-26", "old"), ref("new", "2026-10-26", "old")),
                List.of(), Map.of("new", 2L), Map.of("new", "2026-10-26"), Map.of("old", "old", "new", "old"), (i, d) -> true);
        assertThat(itemOf(r, "old", "2026-10-26")).isEqualTo(2L);
        assertThat(sourceOf(r, "old", "2026-10-26")).isEqualTo("pin");
        assertThat(r.unassigned()).isEmpty();
    }

    @Test
    void groupPinDoesNotReachBackBeforeItsStart() {
        Resolution r = service.resolve(List.of(ref("old", "2026-10-23", "old")), List.of(), Map.of("new", 2L),
                Map.of("new", "2026-10-26"), Map.of("old", "old", "new", "old"), (i, d) -> true);
        assertThat(r.unassigned()).hasSize(1);
    }

    @Test
    void wrapUpDayInheritsRecentManualLink() {
        List<Link> links = List.of(new Link("s", "2026-10-23", 27L, "manual"));
        Resolution r = service.resolve(List.of(ref("s", "2026-10-26", "s")), links, Map.of(), Map.of(), Map.of("s", "s"), (i, d) -> true);
        assertThat(itemOf(r, "s", "2026-10-26")).isEqualTo(27L);
        assertThat(sourceOf(r, "s", "2026-10-26")).isEqualTo("inherit");
    }

    @Test
    void noInheritAfterFourteenDaysOrWhenUserDecided() {
        List<Link> links = List.of(new Link("s", "2026-10-01", 27L, "manual"), new Link("s", "2026-10-27", null, "manual"));
        Resolution r = service.resolve(List.of(ref("s", "2026-10-20", "s"), ref("s", "2026-10-27", "s")), links, Map.of(), Map.of(),
                Map.of("s", "s"), (i, d) -> true);
        // 19일 전 연결은 이어받지 않고, 사용자가 "계획 외"로 확정한 날은 그대로
        assertThat(r.byItem()).isEmpty();
        assertThat(r.unassigned()).hasSize(2);
    }

    @Test
    void noInheritIntoItemNotPlannedThatWeek() {
        // 지난주 할 일(27)은 이번 주 계획에 없다 → 이번 주 보드로 끌어오지 않는다
        List<Link> links = List.of(new Link("s", "2026-10-23", 27L, "manual"));
        Resolution r = service.resolve(List.of(ref("s", "2026-10-26", "s")), links, Map.of(), Map.of(), Map.of("s", "s"),
                (item, day) -> false);
        assertThat(r.byItem()).isEmpty();
        assertThat(r.unassigned()).hasSize(1);
    }
}
