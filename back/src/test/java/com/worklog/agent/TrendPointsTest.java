package com.worklog.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.worklog.report.Period;

class TrendPointsTest {

    @Test
    void 주는_8주_월은_6개월_연은_12개월() {
        assertThat(ObserverService.trendPoints(Period.of("week", "2026-W45"))).hasSize(8)
                .last().extracting(Period::key).isEqualTo("2026-W45");
        assertThat(ObserverService.trendPoints(Period.of("month", "2026-02")))
                .extracting(Period::key).containsExactly("2025-09", "2025-10", "2025-11", "2025-12", "2026-01", "2026-02");
        assertThat(ObserverService.trendPoints(Period.of("year", "2026")))
                .extracting(Period::key).startsWith("2026-01").endsWith("2026-12").hasSize(12);
    }
}
