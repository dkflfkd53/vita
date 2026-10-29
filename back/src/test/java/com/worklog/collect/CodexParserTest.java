package com.worklog.collect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CodexParserTest {

    private static final String TS = "\"timestamp\":\"2026-10-28T01:00:00Z\"";

    @Test
    void sessionMetaAndUserMessage() {
        CodexParser.Line meta = CodexParser.parse("{" + TS + ",\"type\":\"session_meta\",\"payload\":{\"id\":\"abc\",\"cwd\":\"/work/app\"}}", 1);
        assertThat(meta.sessionId()).isEqualTo("abc");
        assertThat(meta.cwd()).isEqualTo("/work/app");
        assertThat(meta.activity()).isFalse(); // 세션을 열기만 한 줄은 작업 시간에 안 넣는다

        CodexParser.Line item = CodexParser.parse("{" + TS + ",\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\","
                + "\"item\":{\"type\":\"UserMessage\",\"id\":\"u1\",\"content\":[{\"type\":\"text\",\"text\":\"로그인 고쳐줘\"}]}}}", 2);
        assertThat(item.userUuid()).isEqualTo("u1");
        assertThat(item.userText()).isEqualTo("로그인 고쳐줘");
        assertThat(item.activity()).isTrue();

        CodexParser.Line old = CodexParser.parse("{" + TS + ",\"type\":\"event_msg\",\"payload\":{\"type\":\"user_message\",\"message\":\"예전 형식\"}}", 3);
        assertThat(old.userText()).isEqualTo("예전 형식");
    }

    @Test
    void execCommandAndApplyPatch() {
        CodexParser.Line cmd = CodexParser.parse("{" + TS + ",\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call\",\"call_id\":\"c1\","
                + "\"name\":\"exec\",\"input\":\"text(await tools.exec_command({cmd:\\\"./gradlew test\\\",max_output_tokens:5000}));\"}}", 4);
        assertThat(cmd.toolStart().name()).isEqualTo("exec_command");
        assertThat(cmd.toolStart().target()).isEqualTo("./gradlew test");

        CodexParser.Line patch = CodexParser.parse("{" + TS + ",\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call\",\"call_id\":\"c2\","
                + "\"name\":\"exec\",\"input\":\"text(await tools.apply_patch(\\\"*** Begin Patch\\\\n*** Update File: /work/app/A.java\\\\n@@\\\\n"
                + "*** Add File: /work/app/B.java\\\\n+x\\\\n*** End Patch\\\"));\"}}", 5);
        assertThat(patch.toolStart().name()).isEqualTo("apply_patch");
        assertThat(patch.patchedFiles()).containsExactly("/work/app/A.java", "/work/app/B.java");
    }

    /** 수천 자짜리 명령(이스케이프 따옴표 섞임)에서 StackOverflowError 가 나 수집이 멈추던 문제 */
    @Test
    void veryLongCommandDoesNotOverflow() {
        String cmd = "echo " + "a\\\\\\\"b ".repeat(200_000);
        CodexParser.Line l = CodexParser.parse("{" + TS + ",\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call\",\"call_id\":\"c9\","
                + "\"name\":\"exec\",\"input\":\"tools.exec_command({cmd:\\\"" + cmd + "\\\"})\"}}", 9);
        assertThat(l.toolStart().name()).isEqualTo("exec_command");
        assertThat(l.toolStart().target()).startsWith("echo a\"b a\"b").hasSizeLessThanOrEqualTo(501);
    }

    @Test
    void toolOutputExitCode() {
        CodexParser.Line ok = CodexParser.parse("{" + TS + ",\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call_output\",\"call_id\":\"c1\","
                + "\"output\":[{\"type\":\"input_text\",\"text\":\"{\\\"exit_code\\\":0,\\\"output\\\":\\\"ok\\\"}\"}]}}", 6);
        assertThat(ok.toolEnd().status()).isEqualTo("ok");
        CodexParser.Line bad = CodexParser.parse("{" + TS + ",\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call_output\",\"call_id\":\"c1\","
                + "\"output\":[{\"type\":\"input_text\",\"text\":\"{\\\"exit_code\\\":127,\\\"output\\\":\\\"not found\\\"}\"}]}}", 7);
        assertThat(bad.toolEnd().status()).isEqualTo("error");
    }

    @Test
    void turnsAndTokens() {
        CodexParser.Line turn = CodexParser.parse("{" + TS + ",\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\",\"duration_ms\":174310}}", 8);
        assertThat(turn.turnMs()).isEqualTo(174310L);
        CodexParser.Line tok = CodexParser.parse("{" + TS + ",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{"
                + "\"total_token_usage\":{\"total_tokens\":16105},\"last_token_usage\":{\"input_tokens\":15980,\"cached_input_tokens\":11136,"
                + "\"cache_write_input_tokens\":0,\"output_tokens\":125}}}}", 9);
        assertThat(tok.tokens().input()).isEqualTo(15980 - 11136);
        assertThat(tok.tokens().cached()).isEqualTo(11136);
        assertThat(tok.tokens().total()).isEqualTo(16105);
        assertThat(CodexParser.parse("{" + TS + ",\"type\":\"event_msg\",\"payload\":{\"type\":\"turn_aborted\"}}", 10).interrupt()).isTrue();
    }
}
