package com.worklog.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.worklog.config.Settings.WorkTextPart;

class WorkTextCleanTest {

    private static WorkTextService.Part part(String id, String title, String source) {
        return new WorkTextService.Part(new WorkTextPart(id, title, source, List.of(), List.of(), List.of(), 12, null, null));
    }

    @Test
    void 차주_계획은_머리_줄_앞_군더더기를_뺀다() throws Exception {
        WorkTextService.Part next = part("next", "차주 계획", "next");
        WorkTextService.Part full = part("full", "전체 업무 내용", "items");
        String text = "<<전체 업무 내용>>\n기간: 10월 5주차\n\n[업무 내용]\n\n- 프로젝트 A\n<<차주 계획>>\n기간: 10월 5주차\n\n[차주 계획]\n\n- 프로젝트 A\n    - 결제 모듈 배포 예정\n";
        var out = WorkTextService.split(text, List.of(full, next));
        assertThat(out.get(full)).startsWith("[업무 내용]");
        assertThat(out.get(next)).startsWith("[차주 계획]").contains("결제 모듈 배포 예정");
    }
}
