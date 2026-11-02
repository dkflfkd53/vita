package com.worklog.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class PeriodTest {

    @Test
    void 연_기간과_앞뒤() {
        Period y = Period.of("year", "2026");
        assertThat(y.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(y.to()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(y.label()).isEqualTo("2026년");
        assertThat(y.kindLabel()).isEqualTo("연간");
        assertThat(y.previous().key()).isEqualTo("2025");
        assertThat(y.next().key()).isEqualTo("2027");
    }

    @Test
    void 월과_주의_앞뒤() {
        assertThat(Period.of("month", "2026-01").previous().key()).isEqualTo("2025-12");
        assertThat(Period.of("month", "2026-12").next().key()).isEqualTo("2027-01");
        assertThat(Period.of("week", "2026-W01").previous().key()).isEqualTo("2025-W52");
        assertThat(Period.containing("month", LocalDate.of(2026, 11, 2)).key()).isEqualTo("2026-11");
    }
}
