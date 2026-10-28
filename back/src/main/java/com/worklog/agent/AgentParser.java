package com.worklog.agent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 원본 한 줄에서 에이전트 관찰 재료만 뽑는다.
 * <ul>
 *   <li>assistant 의 tool_use → 도구 시작(이름·대상)</li>
 *   <li>user 의 tool_result → 도구 끝(성공·실패·거절)</li>
 *   <li>system turn_duration / compact_boundary → 턴 길이·컨텍스트 압축</li>
 *   <li>user 의 "[Request interrupted by user" → 사용자 중단</li>
 * </ul>
 */
public final class AgentParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TARGET_MAX = 200;
    private static final int BASH_MAX = 500;
    private static final int ERROR_MAX = 300;

    private AgentParser() {
    }

    public sealed interface Item permits ToolStart, ToolEnd, Event {
    }

    public record ToolStart(String id, String sessionId, Instant at, String tool, String target, boolean sidechain) implements Item {
    }

    public record ToolEnd(String id, Instant at, String status, String errorText) implements Item {
    }

    public record Event(String sessionId, Instant at, String kind, Long valueMs, String detail) implements Item {
    }

    static List<Item> parse(String line, String fallbackSessionId) {
        boolean candidate = line.contains("\"tool_use\"") || line.contains("\"tool_result\"")
                || line.contains("turn_duration") || line.contains("compact_boundary") || line.contains("[Request interrupted");
        if (!candidate) {
            return List.of();
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            return List.of();
        }
        Instant at;
        try {
            at = Instant.parse(n.path("timestamp").asText());
        } catch (DateTimeParseException e) {
            return List.of();
        }
        String sessionId = n.path("sessionId").asText(fallbackSessionId);
        boolean sidechain = n.path("isSidechain").asBoolean(false);
        List<Item> out = new ArrayList<>();
        switch (n.path("type").asText()) {
            case "assistant" -> {
                for (JsonNode b : n.path("message").path("content")) {
                    if ("tool_use".equals(b.path("type").asText()) && b.hasNonNull("id")) {
                        String tool = b.path("name").asText("?");
                        out.add(new ToolStart(b.path("id").asText(), sessionId, at, tool, target(tool, b.path("input")), sidechain));
                    }
                }
            }
            case "user" -> {
                JsonNode content = n.path("message").path("content");
                if (content.isTextual() && content.asText().startsWith("[Request interrupted")) {
                    out.add(new Event(sessionId, at, "interrupt", null, null));
                }
                for (JsonNode b : content) {
                    String type = b.path("type").asText();
                    if ("tool_result".equals(type) && b.hasNonNull("tool_use_id")) {
                        String text = resultText(b.path("content"));
                        String status = !b.path("is_error").asBoolean(false) ? "ok"
                                : text.contains("doesn't want to proceed") || text.contains("was rejected") ? "rejected" : "error";
                        out.add(new ToolEnd(b.path("tool_use_id").asText(), at, status,
                                "ok".equals(status) ? null : cut(text.strip(), ERROR_MAX)));
                    } else if ("text".equals(type) && b.path("text").asText().startsWith("[Request interrupted")) {
                        out.add(new Event(sessionId, at, "interrupt", null, null));
                    }
                }
            }
            case "system" -> {
                if (sidechain) {
                    break;
                }
                switch (n.path("subtype").asText()) {
                    case "turn_duration" -> out.add(new Event(sessionId, at, "turn", n.path("durationMs").asLong(0), null));
                    case "compact_boundary" -> {
                        JsonNode m = n.path("compactMetadata");
                        out.add(new Event(sessionId, at, "compact", m.path("durationMs").isMissingNode() ? null : m.path("durationMs").asLong(),
                                m.path("trigger").asText("auto") + ":" + m.path("preTokens").asLong(0)));
                    }
                    default -> {
                    }
                }
            }
            default -> {
            }
        }
        return out;
    }

    /** 무엇에 대한 호출인지 한 줄 — Bash 는 명령 첫 줄, 파일 도구는 경로 */
    public static String target(String tool, JsonNode in) {
        String v = switch (tool) {
            case "Bash" -> in.path("command").asText(null);
            case "Read", "Write", "Edit", "NotebookEdit" -> in.path("file_path").asText(in.path("notebook_path").asText(null));
            case "Grep", "Glob" -> in.path("pattern").asText(null);
            case "Agent", "Task" -> in.path("description").asText(in.path("subagent_type").asText(null));
            case "WebFetch" -> in.path("url").asText(null);
            case "WebSearch" -> in.path("query").asText(null);
            case "Skill" -> in.path("skill").asText(null);
            default -> null;
        };
        if (v == null) {
            return null;
        }
        String t = v.strip();
        if ("Bash".equals(tool)) {
            // 여러 줄 명령은 줄을 ⏎ 로 이어 앞부분을 남긴다 — 묶음 이름(signature)을 뒷줄 명령에서 찾을 수 있게
            return cut(t.replaceAll("[ \\t]*\\n[ \\t]*", " ⏎ "), BASH_MAX);
        }
        int nl = t.indexOf('\n');
        return cut(nl < 0 ? t : t.substring(0, nl) + " …", TARGET_MAX);
    }

    private static String resultText(JsonNode c) {
        if (c.isTextual()) {
            return c.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : c) {
            if (b.has("text")) {
                sb.append(b.path("text").asText()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
