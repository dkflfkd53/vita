package com.worklog.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 메모에서 이번 주 줄만 — "10월 4주차" 표기와 예전 "43주" 표기 둘 다 */
class MemoBulletsTest {

    @Test
    void monthWeekPrefix() {
        String memo = "- 10월 3주차: 메뉴 접근 권한 개발\n- 10월 4주차: API 권한 검사, Flyway 스크립트";
        assertThat(WorkTextService.memoBullets(memo, "2026-W43")).containsExactly("API 권한 검사, Flyway 스크립트");
        assertThat(WorkTextService.memoBullets(memo, "2026-W42")).containsExactly("메뉴 접근 권한 개발");
    }

    @Test
    void monthWeekHeaderAndMetaLines() {
        String memo = "10월 3주차 (진행률 70%)\n- 공통 컴포넌트\n10월 4주차 (100%)\n- 브랜치 분리\n- 10월 4주차 차주계획에서 이어짐";
        assertThat(WorkTextService.memoBullets(memo, "2026-W43")).containsExactly("브랜치 분리");
    }

    @Test
    void legacyIsoWeekStillRead() {
        assertThat(WorkTextService.memoBullets("- 42주: 옛 표기\n- 43주: 이번 주", "2026-W43")).containsExactly("이번 주");
    }

    @Test
    void weekBoundaryUsesThursday() {
        // 2026-W40 = 9/28~10/2, 목요일 10/1 → 10월 1주차
        assertThat(WorkTextService.memoBullets("- 10월 1주차: 첫 주\n- 9월 5주차: 아님", "2026-W40")).containsExactly("첫 주");
    }
}
