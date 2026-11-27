package com.worklog.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReportLineTest {

    @Test
    void shortNameStaysAsIs() {
        assertThat(WorkTextService.line("개발서버 무한로딩 해결", " (11/26)")).isEqualTo(" o 개발서버 무한로딩 해결 (11/26)");
    }

    @Test
    void longNameIsCutToThirtyChars() {
        String l = WorkTextService.line("데이터 기반 자동화 프로세스 구축 및 원격 PC 로그 수집", " (~12/4, 진행중)");
        assertThat(l).hasSizeLessThanOrEqualTo(WorkTextService.LINE_MAX).startsWith(" o 데이터").endsWith("… (~12/4, 진행중)");
    }
}
