package com.worklog.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** .ics 규칙 — 75바이트 줄 접기(한글 가운데서 안 자름), 특수문자 이스케이프, 이어진 날짜만 한 일정 */
class CalendarExportTest {

    @Test
    void foldsLongLinesWithoutSplittingHangul() {
        StringBuilder out = new StringBuilder();
        CalendarExportService.line(out, "SUMMARY:" + "가".repeat(40));
        String[] lines = out.toString().split("\r\n");
        assertThat(lines.length).isGreaterThan(1);
        for (String l : lines) {
            assertThat(l.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        assertThat(lines[1]).startsWith(" ");
        String joined = String.join("", lines).replace("\r\n ", "");
        assertThat(joined.replace(" ", "")).contains("가".repeat(40));
    }

    @Test
    void escapesSpecialCharacters() {
        assertThat(CalendarExportService.esc("a,b;c\\d\ne")).isEqualTo("a\\,b\\;c\\\\d\\ne");
    }

    @Test
    void splitsOnGaps() {
        List<LocalDate> days = List.of(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12));
        List<List<LocalDate>> runs = CalendarExportService.runs(days, d -> d);
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0)).hasSize(2);
    }

    @Test
    void timeBlocksSplitOnFifteenMinuteGapsAndDropShortOnes() throws Exception {
        long m = 60_000L;
        var ctor = Class.forName("com.worklog.export.CalendarExportService$Span").getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        java.util.List<Object> spans = new java.util.ArrayList<>();
        // 0~10분 일, 20분에 요청(간격 10분 → 이어짐), 40분짜리 빌드(20~60분) → 하나: 0~60분
        spans.add(ctor.newInstance(0L, 10 * m));
        spans.add(ctor.newInstance(20 * m, 20 * m));
        spans.add(ctor.newInstance(20 * m, 60 * m));
        // 80분 공백 뒤 2분짜리 → 5분 미만이라 뺌
        spans.add(ctor.newInstance(140 * m, 142 * m));
        // 200~230분 → 하나
        spans.add(ctor.newInstance(200 * m, 230 * m));
        @SuppressWarnings({"unchecked", "rawtypes"})
        var blocks = CalendarExportService.blocks((java.util.List) spans);
        assertThat(blocks).hasSize(2);
    }
}
