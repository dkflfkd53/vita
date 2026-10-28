package com.worklog.collect;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Claude Code 세션 jsonl 한 줄을 해석한다. 줄 단위로 끝나므로 증분 수집에 그대로 쓴다.
 * 서브에이전트(sidechain)·도구 결과·슬래시 명령 출력·컨텍스트 이어붙임 요약은 사용자가 한 일이 아니라 버린다.
 */
public final class TranscriptParser {

    static final int MAX_PROMPT_LENGTH = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> EDIT_TOOLS = Set.of("Edit", "Write", "MultiEdit", "NotebookEdit");
    private static final Pattern REMINDER = Pattern.compile("(?s)<system-reminder>.*?</system-reminder>");
    private static final Pattern PASTE = Pattern.compile("(?s)<pasted_content[^>]*>.*?</pasted_content>");
    private static final List<String> NOISE_PREFIXES = List.of(
            "<command-name>", "<command-message>", "<local-command", "<task-notification",
            "<bash-input>", "<bash-stdout>", "<bash-stderr>", "<user-memory-input>",
            "Caveat:", "[Request interrupted", "This session is being continued");

    // 거대한 api-request·첨부 줄까지 JSON 으로 풀지 않도록 문자열로 먼저 거른다
    private static final List<String> INTERESTING = List.of(
            "\"type\":\"user\"", "\"type\":\"assistant\"", "\"type\":\"system\"",
            "\"type\":\"custom-title\"", "\"type\":\"ai-title\"", "\"type\":\"continued-in\"");

    private TranscriptParser() {
    }

    public static Optional<ParsedLine> parse(String line) {
        if (INTERESTING.stream().noneMatch(line::contains)) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        String sessionId = text(node, "sessionId");
        return switch (node.path("type").asText()) {
            case "custom-title" -> meta(ParsedLine.Kind.CUSTOM_TITLE, sessionId, text(node, "customTitle"));
            case "ai-title" -> meta(ParsedLine.Kind.AI_TITLE, sessionId, text(node, "aiTitle"));
            case "continued-in" -> meta(ParsedLine.Kind.CONTINUED_IN, sessionId, text(node, "continuedInSessionId"));
            case "user", "assistant", "system" -> event(node, sessionId);
            default -> Optional.empty();
        };
    }

    private static Optional<ParsedLine> meta(ParsedLine.Kind kind, String sessionId, String value) {
        return value == null ? Optional.empty() : Optional.of(ParsedLine.meta(kind, sessionId, value));
    }

    private static Optional<ParsedLine> event(JsonNode node, String sessionId) {
        if (node.path("isSidechain").asBoolean(false)) {
            return Optional.empty();
        }
        Instant at = instant(text(node, "timestamp"));
        if (at == null) {
            return Optional.empty();
        }
        String type = node.path("type").asText();
        String promptText = null;
        if ("user".equals(type) && !node.path("isMeta").asBoolean(false)
                && !node.path("isCompactSummary").asBoolean(false)) {
            promptText = promptText(node.path("message").path("content"));
        }
        List<ParsedLine.Edit> edits = "assistant".equals(type) ? edits(node) : List.of();
        return Optional.of(new ParsedLine(ParsedLine.Kind.EVENT, sessionId, null, at,
                text(node, "cwd"), text(node, "gitBranch"),
                promptText == null ? null : text(node, "uuid"), promptText, edits));
    }

    static String promptText(JsonNode content) {
        String raw;
        if (content.isTextual()) {
            raw = content.asText();
        } else if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                String blockType = block.path("type").asText();
                if ("tool_result".equals(blockType)) {
                    return null;
                }
                if ("text".equals(blockType)) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(block.path("text").asText());
                }
            }
            raw = sb.toString();
        } else {
            return null;
        }
        String text = REMINDER.matcher(raw).replaceAll("");
        text = PASTE.matcher(text).replaceAll("[붙여넣은 내용]").strip();
        if (text.isEmpty() || NOISE_PREFIXES.stream().anyMatch(text::startsWith)) {
            return null;
        }
        return text.length() > MAX_PROMPT_LENGTH ? text.substring(0, MAX_PROMPT_LENGTH) + "…" : text;
    }

    private static List<ParsedLine.Edit> edits(JsonNode node) {
        JsonNode content = node.path("message").path("content");
        if (!content.isArray()) {
            return List.of();
        }
        List<ParsedLine.Edit> edits = new ArrayList<>();
        for (JsonNode block : content) {
            if (!"tool_use".equals(block.path("type").asText())) {
                continue;
            }
            String tool = block.path("name").asText();
            if (!EDIT_TOOLS.contains(tool)) {
                continue;
            }
            JsonNode input = block.path("input");
            String path = input.hasNonNull("file_path") ? input.get("file_path").asText()
                    : input.path("notebook_path").asText(null);
            String id = text(block, "id");
            if (path != null && id != null) {
                edits.add(new ParsedLine.Edit(id, tool, path));
            }
        }
        return edits;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static Instant instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
