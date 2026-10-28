package com.worklog.collect;

import java.time.Instant;
import java.util.List;

/**
 * 세션 jsonl 한 줄에서 뽑은 것. 제목·이어짐 줄은 value 만, 대화 이벤트 줄은 시각·프롬프트·파일 수정을 채운다.
 */
public record ParsedLine(
        Kind kind,
        String sessionId,
        String value,
        Instant at,
        String cwd,
        String gitBranch,
        String promptUuid,
        String promptText,
        List<Edit> edits
) {

    public enum Kind { CUSTOM_TITLE, AI_TITLE, CONTINUED_IN, EVENT }

    public record Edit(String toolUseId, String tool, String path) {
    }

    static ParsedLine meta(Kind kind, String sessionId, String value) {
        return new ParsedLine(kind, sessionId, value, null, null, null, null, null, List.of());
    }
}
