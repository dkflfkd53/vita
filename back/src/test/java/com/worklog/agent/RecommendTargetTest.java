package com.worklog.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.worklog.agent.ObserverViews.SourceSession;
import com.worklog.agent.ObserverViews.Target;

/** 발생 세션이 한 저장소면 그 저장소 메모리, 여러 저장소면 전역, 모르면 AI 가 고른 대로 */
class RecommendTargetTest {

    private static final Map<String, Target> TARGETS = Map.of(
            "global", new Target("global", "전역", "~/.claude/CLAUDE.md", true, "claude-md"),
            "memory:app", new Target("memory:app", "app 메모리", "~/m/app", true, "memory"),
            "memory:web", new Target("memory:web", "web 메모리", "~/m/web", true, "memory"));

    private static SourceSession s(String repo) {
        return new SourceSession("세션 " + repo, "k-" + repo, repo);
    }

    @Test
    void 한_저장소면_그_메모리() {
        assertThat(ObserverService.recommendTarget("global", List.of(s("app"), s("app")), TARGETS)).isEqualTo("memory:app");
    }

    @Test
    void 여러_저장소면_전역() {
        assertThat(ObserverService.recommendTarget("memory:app", List.of(s("app"), s("web")), TARGETS)).isEqualTo("global");
    }

    @Test
    void 세션을_모르면_고른_대로() {
        assertThat(ObserverService.recommendTarget("memory:web", List.of(), TARGETS)).isEqualTo("memory:web");
        assertThat(ObserverService.recommendTarget("global", List.of(s("other")), TARGETS)).isEqualTo("global");
    }
}
