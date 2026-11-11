package com.worklog.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 규칙 무게 어림과 색인 줄이기 제안 검증 */
class RuleLoadServiceTest {

    @Test
    void estimatesHangulAsOneTokenAndAsciiByThreeAndAHalf() {
        assertThat(RuleLoadService.estTokens("가나다")).isEqualTo(3);
        assertThat(RuleLoadService.estTokens("abcdefg")).isEqualTo(2);
        assertThat(RuleLoadService.estTokens("")).isZero();
    }

    @Test
    void keepsOnlyShorterLinesWithTheSameLink() throws Exception {
        String a = "- [배포 규칙](deploy.md) — " + "아주 긴 설명 ".repeat(30);
        String b = "- [화면 규칙](screen.md) — " + "긴 설명 ".repeat(30);
        String c = "- [기타](etc.md) — " + "설명 ".repeat(30);
        String out = """
                [{"n":1,"newLine":"- [배포](deploy.md) — 배포 절차·금지","moved":"","reason":"이력 뺌"},
                 {"n":2,"newLine":"- [화면](other.md) — 링크 바뀜","moved":"","reason":"x"},
                 {"n":3,"newLine":"- [기타](etc.md) — %s","moved":"","reason":"거의 그대로"},
                 {"n":1,"newLine":"- [배포](deploy.md) — 중복","moved":"","reason":"x"}]
                """.formatted(c.substring(c.indexOf("— ") + 2, c.length() - 3));
        List<String[]> list = RuleLoadService.parseDiet(out, List.of(a, b, c));
        assertThat(list).hasSize(1);
        assertThat(list.get(0)[0]).isEqualTo(a);
        assertThat(list.get(0)[1]).isEqualTo("- [배포](deploy.md) — 배포 절차·금지");
        assertThat(list.get(0)[2]).isNull();
    }

    @Test
    void linkOfIgnoresPathsAndUrls() {
        assertThat(RuleLoadService.linkOf("- [a](b.md) — c")).isEqualTo("b.md");
        assertThat(RuleLoadService.linkOf("- [a](../x/b.md)")).isNull();
        assertThat(RuleLoadService.linkOf("- 링크 없음")).isNull();
    }
}
