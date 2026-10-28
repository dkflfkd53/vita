package com.worklog.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.worklog.agent.AgentParser.Event;
import com.worklog.agent.AgentParser.Item;
import com.worklog.agent.AgentParser.ToolEnd;
import com.worklog.agent.AgentParser.ToolStart;

class AgentParserTest {

    @Test
    void toolUseAndResult() {
        List<Item> start = AgentParser.parse("""
                {"type":"assistant","sessionId":"s1","timestamp":"2026-10-27T01:00:00Z","isSidechain":false,
                 "message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"cd ~/x && ./gradlew test\\necho hi"}}]}}
                """.replace("\n", ""), "fb");
        assertThat(start).singleElement().isInstanceOfSatisfying(ToolStart.class, s -> {
            assertThat(s.sessionId()).isEqualTo("s1");
            assertThat(s.tool()).isEqualTo("Bash");
            assertThat(s.target()).isEqualTo("cd ~/x && ./gradlew test ⏎ echo hi");
        });

        List<Item> end = AgentParser.parse("""
                {"type":"user","timestamp":"2026-10-27T01:00:05Z",
                 "message":{"content":[{"type":"tool_result","tool_use_id":"t1","is_error":true,"content":"Exit code 1\\nFAILED"}]}}
                """.replace("\n", ""), "fb");
        assertThat(end).singleElement().isInstanceOfSatisfying(ToolEnd.class, e -> {
            assertThat(e.status()).isEqualTo("error");
            assertThat(e.errorText()).startsWith("Exit code 1");
        });
    }

    @Test
    void rejectedToolIsNotAnError() {
        List<Item> end = AgentParser.parse("""
                {"type":"user","timestamp":"2026-10-27T01:00:05Z","message":{"content":[{"type":"tool_result","tool_use_id":"t2",
                 "is_error":true,"content":"The user doesn't want to proceed with this tool use. The tool use was rejected"}]}}
                """.replace("\n", ""), "fb");
        assertThat(((ToolEnd) end.get(0)).status()).isEqualTo("rejected");
    }

    @Test
    void turnCompactAndInterrupt() {
        assertThat(AgentParser.parse("{\"type\":\"system\",\"subtype\":\"turn_duration\",\"durationMs\":1500,\"timestamp\":\"2026-10-27T01:00:00Z\",\"sessionId\":\"s\"}", "fb"))
                .singleElement().isInstanceOfSatisfying(Event.class, e -> assertThat(e.valueMs()).isEqualTo(1500));
        assertThat(AgentParser.parse("{\"type\":\"system\",\"subtype\":\"compact_boundary\",\"compactMetadata\":{\"trigger\":\"auto\",\"preTokens\":900},\"timestamp\":\"2026-10-27T01:00:00Z\"}", "fb"))
                .singleElement().isInstanceOfSatisfying(Event.class, e -> {
                    assertThat(e.kind()).isEqualTo("compact");
                    assertThat(e.detail()).isEqualTo("auto:900");
                    assertThat(e.sessionId()).isEqualTo("fb");
                });
        assertThat(AgentParser.parse("{\"type\":\"user\",\"timestamp\":\"2026-10-27T01:00:00Z\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"[Request interrupted by user]\"}]}}", "fb"))
                .singleElement().isInstanceOfSatisfying(Event.class, e -> assertThat(e.kind()).isEqualTo("interrupt"));
        // 서브에이전트의 턴은 본체 시간에 섞지 않는다
        assertThat(AgentParser.parse("{\"type\":\"system\",\"subtype\":\"turn_duration\",\"isSidechain\":true,\"durationMs\":1,\"timestamp\":\"2026-10-27T01:00:00Z\"}", "fb")).isEmpty();
    }

    @Test
    void commandSignature() {
        assertThat(ObserverCalc.signature("cd ~/a && ./gradlew compileJava -q")).isEqualTo("./gradlew compileJava");
        assertThat(ObserverCalc.signature("sqlite3 ~/.claude-worklog/worklog.db \"select 1\"")).isEqualTo("sqlite3");
        assertThat(ObserverCalc.signature("npx tsc -p tsconfig.app.json")).isEqualTo("npx tsc");
        assertThat(ObserverCalc.signature("python3 - <<'EOF' …")).isEqualTo("python3");
        assertThat(ObserverCalc.signature("S=/tmp/x; cd $S && cat > a.java <<'EOF' ⏎ class A {} ⏎ EOF ⏎ ./gradlew bootJar -q")).isEqualTo("./gradlew bootJar");
        assertThat(ObserverCalc.signature("export PATH=/x:$PATH; for i in 1 2; do curl -s localhost; done")).isEqualTo("curl");
        assertThat(ObserverCalc.signature("sleep 30; tail -5 log")).isEqualTo("sleep");
        assertThat(ObserverCalc.signature("for f in *.md; do wc -l $f; done")).isEqualTo("for 반복");
    }

    @Test
    void correctionWords() {
        assertThat(ObserverCalc.isCorrection("월 버튼이랑 화살표가 클릭이안되는데")).isTrue();
        assertThat(ObserverCalc.isCorrection("ㄴㄴ 지금 모양 말고")).isTrue();
        assertThat(ObserverCalc.isCorrection("엥 그니까 3개가 나와야 한다고")).isTrue();
        assertThat(ObserverCalc.isCorrection("토큰 사용량도 책정 가능해?")).isFalse();
    }

    @Test
    void verdicts() {
        assertThat(ObserverService.verdict("error_rate", 10.0, 8.0)).isEqualTo("개선");
        assertThat(ObserverService.verdict("error_rate", 10.0, 9.5)).isEqualTo("변화 없음");
        assertThat(ObserverService.verdict("error_rate", 10.0, 12.0)).isEqualTo("악화");
        assertThat(ObserverService.verdict("avg_turn", 10.0, 1.0)).isEqualTo("참고 지표");
    }
}
