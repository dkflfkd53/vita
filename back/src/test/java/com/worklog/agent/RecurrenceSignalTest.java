package com.worklog.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 재발 신호 — 짧은 낱말은 버리고, 명령·오류 글에 대소문자 무시로 들어 있으면 재발 */
class RecurrenceSignalTest {

    @Test
    void dropsTooShortAndDuplicateSignals() {
        assertThat(ObserverService.readSignals("[\"ab\",\"sleep 600\",\"sleep 600\",\" push origin HEAD:main \"]"))
                .containsExactly("sleep 600", "push origin HEAD:main");
        assertThat(ObserverService.signalsJson(List.of("x", " "))).isNull();
        assertThat(ObserverService.readSignals((String) null)).isEmpty();
    }

    @Test
    void matchesCaseInsensitivelyInAnyText() {
        List<String> keys = List.of("push origin head:main", "no such file");
        assertThat(ObserverService.hit(keys, "git push origin HEAD:main", null)).isTrue();
        assertThat(ObserverService.hit(keys, "ls", "cat: x: No such file or directory")).isTrue();
        assertThat(ObserverService.hit(keys, "git push origin develop", "ok")).isFalse();
    }

    @Test
    void plainSignalsCountOnlyFailuresWhileCmdSignalsCountAnyRun() {
        List<String> keys = List.of("docker load", "cmd:sleep 600");
        assertThat(ObserverService.toolHit(keys, "ok", "docker load -i x.tar", null)).isFalse();
        assertThat(ObserverService.toolHit(keys, "error", "docker load -i x.tar", "boom")).isTrue();
        assertThat(ObserverService.toolHit(keys, "ok", "sleep 600 && curl x", null)).isTrue();
        assertThat(ObserverService.promptHit(keys, "sleep 600 하지 마")).isFalse();
    }
}
