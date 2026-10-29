package com.worklog.collect;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Codex CLI 세션 원본(~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl) 한 줄을 읽는다.
 * <ul>
 *   <li>session_meta / turn_context → 세션 id·작업 폴더·모델</li>
 *   <li>event_msg user_message, item_completed UserMessage → 내 요청</li>
 *   <li>custom_tool_call(exec 안의 tools.exec_command·apply_patch …) / function_call → 도구 호출, apply_patch 의 파일 → 파일 수정</li>
 *   <li>*_call_output → 도구 끝(exit_code ≠ 0 이면 실패)</li>
 *   <li>task_complete → 턴 길이, turn_aborted → 중단, compacted → 컨텍스트 압축</li>
 *   <li>token_count → 응답 토큰(누적 합계가 바뀔 때만 새 응답)</li>
 * </ul>
 * 대화·도구·토큰 줄만 작업 시간 계산용 이벤트(activity)로 쓴다 — 세션을 열기만 한 설정 줄(session_meta·turn_context 등)은 빼서
 * 아무것도 안 한 빈 세션이 기록에 남지 않게.
 */
public final class CodexParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern INNER_TOOL = Pattern.compile("tools\\.(\\w+)\\(");
    /** 앞 600자만 — 반복 그룹을 끝없이 돌리면 긴 명령(수천 자)에서 StackOverflowError 가 난다. 화면엔 500자만 쓴다 */
    private static final Pattern CMD = Pattern.compile("cmd\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,600})");
    private static final Pattern PATCH_FILE = Pattern.compile("\\*\\*\\* (?:Add|Update|Delete) File: (.+?)(?:\\\\n|\\n|\")");
    private static final Pattern EXIT_CODE = Pattern.compile("exit_code\\\\?\"?\\s*:\\s*(-?\\d+)");
    private static final int TARGET_MAX = 500;

    private CodexParser() {
    }

    public record Line(Instant at, String sessionId, String cwd, String model, String userUuid, String userText,
                       Tool toolStart, ToolEnd toolEnd, List<String> patchedFiles, Long turnMs, boolean interrupt,
                       boolean compact, Tokens tokens, boolean activity) {
    }

    public record Tool(String callId, String name, String target) {
    }

    public record ToolEnd(String callId, String status, String error) {
    }

    /** input = 캐시 안 된 입력, cached = 캐시 읽기, total = 세션 누적 합계(같은 응답이 두 번 찍히면 같다) */
    public record Tokens(long input, long cached, long cacheWrite, long output, long total) {
    }

    public static Line parse(String raw, int ordinal) {
        JsonNode n;
        try {
            n = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return null;
        }
        Instant at;
        try {
            at = Instant.parse(n.path("timestamp").asText());
        } catch (DateTimeParseException e) {
            return null;
        }
        String type = n.path("type").asText();
        JsonNode p = n.path("payload");
        String pt = p.path("type").asText("");
        String sid = null, cwd = null, model = null, userUuid = null, userText = null;
        Tool start = null;
        ToolEnd end = null;
        List<String> files = List.of();
        Long turnMs = null;
        boolean interrupt = false, compact = false;
        Tokens tokens = null;
        switch (type) {
            case "session_meta" -> {
                sid = p.path("id").asText(null);
                cwd = p.path("cwd").asText(null);
            }
            case "turn_context" -> {
                cwd = p.path("cwd").asText(null);
                model = p.path("model").asText(null);
            }
            case "compacted" -> compact = true;
            case "event_msg" -> {
                switch (pt) {
                    case "thread_settings_applied" -> model = p.path("thread_settings").path("model").asText(null);
                    case "user_message" -> {
                        userText = p.path("message").asText(null);
                        userUuid = "codex-um-" + ordinal + "-" + at.toEpochMilli();
                    }
                    case "item_completed" -> {
                        JsonNode item = p.path("item");
                        if ("UserMessage".equals(item.path("type").asText())) {
                            StringBuilder sb = new StringBuilder();
                            for (JsonNode c : item.path("content")) {
                                if (c.has("text")) {
                                    sb.append(c.path("text").asText()).append('\n');
                                }
                            }
                            userText = sb.toString().strip();
                            userUuid = item.path("id").asText(null);
                        }
                    }
                    case "task_complete" -> turnMs = p.path("duration_ms").isNumber() ? p.path("duration_ms").asLong() : null;
                    case "turn_aborted" -> interrupt = true;
                    case "token_count" -> {
                        JsonNode last = p.path("info").path("last_token_usage");
                        JsonNode total = p.path("info").path("total_token_usage");
                        if (last.isObject() && total.isObject()) {
                            long in = last.path("input_tokens").asLong(0);
                            long cached = last.path("cached_input_tokens").asLong(0);
                            tokens = new Tokens(Math.max(0, in - cached), cached, last.path("cache_write_input_tokens").asLong(0),
                                    last.path("output_tokens").asLong(0), total.path("total_tokens").asLong(0));
                        }
                    }
                    default -> {
                    }
                }
            }
            case "response_item" -> {
                switch (pt) {
                    case "custom_tool_call", "function_call" -> {
                        String callId = p.path("call_id").asText(null);
                        if (callId != null) {
                            String input = "custom_tool_call".equals(pt) ? p.path("input").asText("") : p.path("arguments").asText("");
                            String name = p.path("name").asText("?");
                            Matcher inner = INNER_TOOL.matcher(input);
                            if (inner.find()) {
                                name = inner.group(1); // exec 안에서 실제로 부른 도구(exec_command·apply_patch …)
                            }
                            files = patchedFiles(input);
                            start = new Tool(callId, name, target(name, input, files));
                        }
                    }
                    case "custom_tool_call_output", "function_call_output" -> {
                        String callId = p.path("call_id").asText(null);
                        if (callId != null) {
                            String text = outputText(p.path("output"));
                            boolean failed = text.startsWith("Script failed") || text.contains("\nError:");
                            Matcher m = EXIT_CODE.matcher(text);
                            while (m.find()) {
                                if (!"0".equals(m.group(1))) {
                                    failed = true;
                                }
                            }
                            end = new ToolEnd(callId, failed ? "error" : "ok", failed ? cut(text.strip(), 300) : null);
                        }
                    }
                    default -> {
                    }
                }
            }
            default -> {
            }
        }
        boolean activity = "response_item".equals(type) || "compacted".equals(type)
                || ("event_msg".equals(type) && !"thread_settings_applied".equals(pt));
        return new Line(at, sid, cwd, model, userUuid, blankToNull(userText), start, end, files, turnMs, interrupt, compact, tokens,
                activity);
    }

    static List<String> patchedFiles(String input) {
        if (!input.contains("*** ")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = PATCH_FILE.matcher(input);
        while (m.find()) {
            out.add(m.group(1).strip());
        }
        return out;
    }

    /** 무엇에 대한 호출인지 한 줄 — 명령이면 명령(앞부분), 패치면 첫 파일 */
    static String target(String name, String input, List<String> files) {
        if (!files.isEmpty()) {
            return files.getFirst() + (files.size() > 1 ? " 외 " + (files.size() - 1) : "");
        }
        Matcher m = CMD.matcher(input);
        if (m.find()) {
            String cmd = m.group(1).replace("\\n", " ⏎ ").replace("\\\"", "\"").replace("\\\\", "\\");
            return cut(cmd.strip(), TARGET_MAX);
        }
        return null;
    }

    private static String outputText(JsonNode out) {
        if (out.isTextual()) {
            return out.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : out) {
            if (b.has("text")) {
                sb.append(b.path("text").asText()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
