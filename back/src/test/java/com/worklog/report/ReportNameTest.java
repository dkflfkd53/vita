package com.worklog.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.worklog.work.WorkViews.ItemSession;
import com.worklog.work.WorkViews.ItemView;

/** 주간보고 글 업무명 = 가장 오래 일한 연결 세션 이름, 요청자는 할 일 이름에서 */
class ReportNameTest {

    private static ItemSession s(String title, long sec) {
        return new ItemSession("s-" + title, title, "앱", null, true, false, false, sec, 0, List.of());
    }

    private static ItemView item(String title, String status, String due, String doneAt, List<ItemSession> sessions) {
        return new ItemView(1, title, "앱", null, "MEDIUM", status, due, null, null, doneAt, null, true, 0, List.of(), 0, sessions, List.of());
    }

    @Test
    void 세션_이름을_쓰고_요청자와_완료일을_괄호로() {
        ItemView i = item("김철수님 요구사항2 해결", "DONE", null, "2026-11-04T05:00:00Z",
                List.of(s("주문 내역 엑셀 다운로드 불가", 3578), s("짧은 확인", 60)));
        assertThat(WorkTextService.reportName(i)).isEqualTo("주문 내역 엑셀 다운로드 불가");
        assertThat(WorkTextService.tag(i)).isEqualTo(" (김철수님 요청, ~11/4 완료)");
    }

    @Test
    void 세션_이름_끝_사람_이름은_빼고_요청자는_한_번만() {
        ItemView i = item("이영희님 결제 화면 요구사항 해결", "IN_PROGRESS", "2026-11-14", null, List.of(s("결제 화면 금액 표시 버그 수정(이영희)", 4736)));
        assertThat(WorkTextService.reportName(i)).isEqualTo("결제 화면 금액 표시 버그 수정");
        assertThat(WorkTextService.tag(i)).isEqualTo(" (이영희님 요청, ~11/14 마감)");
    }

    @Test
    void 세션이_없거나_이름_구실을_못_하면_할_일_이름() {
        assertThat(WorkTextService.reportName(item("알림 화면 리액트 전환 및 배포", "IN_PROGRESS", null, null,
                List.of(s("리액트 전환2", 21481))))).isEqualTo("알림 화면 리액트 전환 및 배포");
        assertThat(WorkTextService.reportName(item("서버 백업 방식 고민", "PLANNED", null, null, List.of()))).isEqualTo("서버 백업 방식 고민");
        assertThat(WorkTextService.tag(item("서버 백업 방식 고민", "PLANNED", null, null, List.of()))).isEmpty();
    }
}
