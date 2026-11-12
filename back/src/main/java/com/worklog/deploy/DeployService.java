package com.worklog.deploy;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worklog.config.WorklogProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CI/CD 배포 기록 — GitLab 의 배포 잡(예: 운영 배포 수동 잡) 실행을 10분마다 옮겨 적어 보드·캘린더·주간보고 글에 보인다.
 * 세션으로 배포하던 때는 «반복 업무 세션»으로 잡혔지만, 파이프라인 버튼으로 배포하면 세션이 없어 따로 읽어 온다.
 * <p>
 * 설정(GitLab 주소·토큰·원천)은 setting 표 key=deploy 에만 둔다(회사 값은 코드에 넣지 않는다). 토큰은 화면에 가려서 준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeployService {

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String KEY = "deploy";
    private static final String MASK = "********";

    private final JdbcClient jdbc;
    private final WorklogProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private volatile String lastError;
    private volatile String lastSyncAt;

    /**
     * 원천 하나 = GitLab 프로젝트 경로(그룹/이름) + 잡 이름. label = 화면 이름(예: 운영 배포), category = 보드의 어느 블록에, short = 짧은 이름(back·front)
     */
    public record Source(String project, String job, String label, String category, String shortName) {
    }

    public record Config(String baseUrl, String token, List<Source> sources) {
    }

    public record Event(String project, long jobId, String source, String label, String category, String shortName, String status,
                        String ref, String sha, String startedAt, String finishedAt, String day, Double duration, String user,
                        String webUrl) {
    }

    public record SyncResult(int added, String error, String syncedAt) {
    }

    // ───────── 설정 ─────────

    public Config config() {
        return jdbc.sql("SELECT value FROM setting WHERE key = ?").param(KEY).query(String.class).optional().map(v -> {
            try {
                return MAPPER.readValue(v, Config.class);
            } catch (IOException e) {
                return null;
            }
        }).orElse(new Config(null, null, List.of()));
    }

    /** 화면용 — 토큰은 가린다 */
    public Config configForView() {
        Config c = config();
        return new Config(c.baseUrl(), c.token() == null || c.token().isBlank() ? null : MASK, c.sources() == null ? List.of() : c.sources());
    }

    /** 토큰 칸이 가린 값 그대로면 원래 토큰을 둔다 */
    public void save(Config in) throws IOException {
        Config old = config();
        String token = in.token() == null || MASK.equals(in.token()) ? old.token() : blankToNull(in.token());
        List<Source> sources = new ArrayList<>();
        for (Source s : in.sources() == null ? List.<Source>of() : in.sources()) {
            if (s.project() != null && !s.project().isBlank() && s.job() != null && !s.job().isBlank()) {
                sources.add(new Source(s.project().strip().replaceAll("^/+|/+$", ""), s.job().strip(), blankToNull(s.label()),
                        blankToNull(s.category()), blankToNull(s.shortName())));
            }
        }
        String base = blankToNull(in.baseUrl());
        Config c = new Config(base == null ? null : base.replaceAll("/+$", ""), token, sources);
        jdbc.sql("INSERT INTO setting (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at")
                .params(KEY, MAPPER.writeValueAsString(c), Instant.now().toString()).update();
    }

    // ───────── 가져오기 ─────────

    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void scheduled() {
        sync();
    }

    /** 원천마다 최근 잡 100개에서 그 이름의 끝난 실행을 옮겨 적는다(이미 있는 건 상태만 갱신) */
    public synchronized SyncResult sync() {
        Config c = config();
        if (c.baseUrl() == null || c.token() == null || c.sources() == null || c.sources().isEmpty()) {
            return new SyncResult(0, null, lastSyncAt);
        }
        int added = 0;
        List<String> errors = new ArrayList<>();
        for (Source s : c.sources()) {
            try {
                added += syncSource(c, s);
            } catch (IOException | RuntimeException e) {
                errors.add(s.project() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        lastError = errors.isEmpty() ? null : String.join(" · ", errors);
        lastSyncAt = Instant.now().toString();
        if (lastError != null) {
            log.warn("배포 기록 가져오기 실패: {}", lastError);
        }
        return new SyncResult(added, lastError, lastSyncAt);
    }

    private int syncSource(Config c, Source s) throws IOException, InterruptedException {
        String url = c.baseUrl() + "/api/v4/projects/" + URLEncoder.encode(s.project(), StandardCharsets.UTF_8)
                + "/jobs?per_page=100&scope[]=success&scope[]=failed&scope[]=canceled";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.replace("[", "%5B").replace("]", "%5D")))
                .header("PRIVATE-TOKEN", c.token()).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("GitLab " + res.statusCode() + (res.statusCode() == 401 ? " (토큰 확인)" : res.statusCode() == 404 ? " (프로젝트 경로 확인)" : ""));
        }
        int added = 0;
        for (JsonNode j : MAPPER.readTree(res.body())) {
            if (!s.job().equals(j.path("name").asText())) {
                continue;
            }
            String finished = text(j, "finished_at");
            String day = finished == null ? null : LocalDate.ofInstant(Instant.parse(normalize(finished)), props.zone()).toString();
            int n = jdbc.sql("""
                    INSERT INTO deploy_event (project, job_id, source, status, ref, sha, started_at, finished_at, day, duration, user_name, web_url)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(project, job_id) DO UPDATE SET status = excluded.status, finished_at = excluded.finished_at,
                        day = excluded.day, duration = excluded.duration, source = excluded.source
                    """).params(s.project(), j.path("id").asLong(), s.project() + "#" + s.job(), j.path("status").asText(),
                    text(j, "ref"), j.path("commit").path("short_id").asText(null), text(j, "started_at"), finished, day,
                    j.path("duration").isNumber() ? j.path("duration").asDouble() : null, j.path("user").path("name").asText(null),
                    text(j, "web_url")).update();
            added += n;
        }
        return added;
    }

    // ───────── 읽기 ─────────

    /** 기간의 배포(끝난 날 기준). 원천 설정의 이름·카테고리를 붙인다 — 설정에서 지운 원천은 빠진다 */
    public List<Event> events(String from, String to) {
        List<Source> sources = Optional.ofNullable(config().sources()).orElse(List.of());
        return jdbc.sql("""
                SELECT project, job_id, source, status, ref, sha, started_at, finished_at, day, duration, user_name, web_url
                FROM deploy_event WHERE day >= ? AND day <= ? ORDER BY finished_at
                """).params(from, to).query((rs, i) -> {
            String src = rs.getString(3);
            Source s = sources.stream().filter(x -> (x.project() + "#" + x.job()).equals(src)).findFirst().orElse(null);
            if (s == null) {
                return null;
            }
            double d = rs.getDouble(10);
            return new Event(rs.getString(1), rs.getLong(2), src, s.label() == null ? s.job() : s.label(), s.category(),
                    s.shortName() == null ? s.project().replaceAll(".*/", "") : s.shortName(), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.wasNull() ? null : d, rs.getString(11),
                    rs.getString(12));
        }).list().stream().filter(e -> e != null).toList();
    }

    public String lastError() {
        return lastError;
    }

    public String lastSyncAt() {
        return lastSyncAt;
    }

    private static String text(JsonNode n, String f) {
        return n.path(f).isNull() || n.path(f).isMissingNode() ? null : n.path(f).asText();
    }

    /** GitLab 은 2026-11-10T14:20:01.123+09:00 같은 오프셋 시각도 준다 */
    private static String normalize(String iso) {
        return java.time.OffsetDateTime.parse(iso).toInstant().toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
