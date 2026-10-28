package com.worklog.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.worklog.config.Settings.ReportTemplate;

class TemplatePromptTest {

    private static final String FORMAT = """
            ## 이번 달 성과
            - <성과>

            ## 다음 달 계획
            - <할 일>""";

    @Test
    void 양식_틀을_그대로_넣고_범위와_지침을_붙인다() {
        ReportTemplate t = new ReportTemplate("tpl-1", "월간보고", "month", FORMAT, "업무마다 2줄\n수치는 괄호", List.of("프로젝트 A"));
        String p = ReportService.templatePrompt(Period.of("month", "2026-10"), t, "## 통계\n- 작업 시간 합계 10.0시간");

        assertThat(p).contains("월간 업무 보고서 \"월간보고\"", "2026-10-01 ~ 2026-10-31");
        assertThat(p).contains("===== 양식 시작 =====\n" + FORMAT + "\n===== 양식 끝 =====");
        assertThat(p).contains("카테고리 프로젝트 A 에 속한");
        assertThat(p).contains("작성 지침(사용자): 업무마다 2줄\n  수치는 괄호");
        assertThat(p).endsWith("- 작업 시간 합계 10.0시간\n");
    }

    @Test
    void 범위_지침이_없으면_그_줄이_없다() {
        ReportTemplate t = new ReportTemplate("tpl-2", "주간 요약", "week", FORMAT, null, List.of());
        String p = ReportService.templatePrompt(Period.of("week", "2026-W44"), t, "입력");

        assertThat(p).doesNotContain("범위:", "작성 지침(사용자)");
        assertThat(p).contains("주간 업무 보고서 \"주간 요약\"");
    }
}
