package com.worklog.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.worklog.work.WorkViews.ItemView;

class SubtypeGroupTest {

    private static ItemView item(long id, String subtype) {
        return new ItemView(id, "할 일 " + id, "앱", subtype, "MEDIUM", "IN_PROGRESS", null, null, null, null, null, true, 0,
                List.of(), 0, List.of(), List.of());
    }

    @Test
    void 분류_순서대로_묶고_빈_분류는_빼고_미분류는_끝() {
        var m = WorkTextService.bySubtype(List.of(item(1, "오류"), item(2, null), item(3, "기능개발"), item(4, "없는분류")),
                List.of("기능개발", "디비 보정", "오류"));
        assertThat(m.keySet()).containsExactly("기능개발", "오류", WorkTextService.UNSORTED);
        assertThat(m.get(WorkTextService.UNSORTED)).extracting(ItemView::id).containsExactly(2L, 4L);
    }

    @Test
    void 세부_분류가_없으면_한_묶음() {
        var m = WorkTextService.bySubtype(List.of(item(1, null)), List.of());
        assertThat(m).containsOnlyKeys((String) null);
    }
}
