package com.worklog.google;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoogleCalendarIdTest {

    @Test
    void importedIdMatchesGoogleImport() {
        // 구글 캘린더 «가져오기»로 실제로 생긴 일정 id
        assertThat(GoogleCalendarService.importedId("vita-dep--1500280072-35236@vita"))
                .isEqualTo("_epkn8o9dchin0b9d64qj0c1i70o30dpi5kpjachj6p07cqbkc4");
        assertThat(GoogleCalendarService.importedId("vita-blk-99626cdd-dd09-4117-b3c1-75f9146ab760-1795480686769@vita"))
                .isEqualTo("_epkn8o9dc9m6mb9p74r34dj3chi2qp3460siqd1h64riqohjccoiqdplcosj2d1mc5h3edhg5kojee9l6gs30dho6orjcea0epkn8o8");
    }
}
