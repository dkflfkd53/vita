package com.worklog.usage;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** assistant 줄의 message.usage 를 뽑는다. 합성 응답(<synthetic>)은 실제 호출이 아니라 버린다 */
public final class UsageParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UsageParser() {
    }

    static Optional<UsageRow> parse(String line, String fallbackSessionId) {
        if (!line.contains("\"usage\"") || !line.contains("\"type\":\"assistant\"")) {
            return Optional.empty();
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (!"assistant".equals(n.path("type").asText())) {
            return Optional.empty();
        }
        JsonNode m = n.path("message");
        JsonNode u = m.path("usage");
        String id = m.path("id").asText(null);
        String model = m.path("model").asText(null);
        if (id == null || model == null || model.startsWith("<") || !u.isObject()) {
            return Optional.empty();
        }
        Instant at;
        try {
            at = Instant.parse(n.path("timestamp").asText());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        long write = u.path("cache_creation_input_tokens").asLong(0);
        JsonNode cc = u.path("cache_creation");
        long w1h = cc.path("ephemeral_1h_input_tokens").asLong(0);
        long w5m = cc.has("ephemeral_5m_input_tokens") ? cc.path("ephemeral_5m_input_tokens").asLong(0) : Math.max(0, write - w1h);
        String sessionId = n.path("sessionId").asText(fallbackSessionId);
        return Optional.of(new UsageRow(id, sessionId, at, model,
                u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0),
                u.path("cache_read_input_tokens").asLong(0), w5m, w1h, n.path("isSidechain").asBoolean(false)));
    }

    public record UsageRow(String messageId, String sessionId, Instant at, String model, long input, long output,
                    long cacheRead, long cacheWrite5m, long cacheWrite1h, boolean sidechain) {
    }
}
