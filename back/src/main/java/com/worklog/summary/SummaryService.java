package com.worklog.summary;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worklog.collect.CollectScheduler;
import com.worklog.config.WorklogProperties;
import com.worklog.query.QueryStore;
import com.worklog.query.Rows.DaySummaryRow;
import com.worklog.query.Rows.SessionDayRow;
import com.worklog.query.Views.DayDetail;
import com.worklog.query.Views.DaySession;
import com.worklog.query.WorkQueryService;
import com.worklog.report.Period;
import com.worklog.work.ClassifyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 하루 요약. 평소엔 규칙 추출만 쌓고, 지난 날 중 요약이 빈 날을 기동 시·매일 09시에 claude 로 채운다.
 * 오늘 같은 진행 중인 날은 화면에서 "지금 요약"을 눌렀을 때만 만든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    public static final String RULES = """
            작성 규칙:
            - 사용자가 실제로 진행한 일과 그 결과(완료·진행 중·막힌 점)를 쓴다. 대화 과정이나 Claude 의 행동 묘사는 쓰지 않는다.
            - 입력에 있는 사실만 쓴다. 결과를 추측해서 지어내지 않는다.
            - 비밀번호·토큰·API 키·접속 계정·IP 주소 같은 민감정보는 절대 옮기지 않는다.
            - 한국어 업무 보고체(~함, ~완료, ~진행 중)로 짧게 쓴다.
            """;
    private static final int MAX_DIGEST_CHARS = 60_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorklogProperties props;
    private final WorkQueryService query;
    private final QueryStore store;
    private final ClaudeCli claude;
    private final JobService jobs;
    private final CollectScheduler collector;
    private final ClassifyService classify;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (props.autoSummary()) {
            submitCatchUp();
        }
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "${worklog.zone}")
    public void daily() {
        if (props.autoSummary()) {
            submitCatchUp();
        }
    }

    public JobService.Job submitCatchUp() {
        return jobs.submit("catch-up", "지난 날 요약 채우기", progress -> {
            collector.collectAndWait();
            LocalDate today = LocalDate.now(props.zone());
            List<LocalDate> days = missingDays(today.minusDays(props.summaryLookbackDays()), today);
            int done = 0;
            for (LocalDate day : days) {
                progress.update(day + " 요약 중 (" + (done + 1) + "/" + days.size() + ")");
                try {
                    summarize(day);
                    done++;
                } catch (IOException e) {
                    log.warn("{} 요약 실패: {}", day, e.getMessage());
                }
            }
            // 요약이 생기면 업무 분류 근거가 좋아진다 — 지난주·이번 주 미분류분을 이어서 붙인다
            if (props.autoClassify()) {
                classify.submit(Period.weekKey(today.minusDays(7)), false);
                classify.submit(Period.weekKey(today), false);
            }
            return days.isEmpty() ? "채울 날 없음" : done + "/" + days.size() + "일 요약 완료";
        });
    }

    public JobService.Job submitDay(LocalDate day) {
        return jobs.submit("day:" + day, day + " 요약", progress -> {
            collector.collectAndWait();
            return summarize(day) ? day + " 요약 완료" : day + " 은 기록이 없음";
        });
    }

    /** from~to 중 오늘 이전이고, 기록은 있는데 요약이 없는 날 — 오래된 순 */
    public List<LocalDate> missingDays(LocalDate from, LocalDate to) {
        LocalDate yesterday = LocalDate.now(props.zone()).minusDays(1);
        if (to.isAfter(yesterday)) {
            to = yesterday;
        }
        if (from.isAfter(to)) {
            return List.of();
        }
        Set<String> done = store.daySummaries(from.toString(), to.toString()).stream()
                .map(DaySummaryRow::day).collect(Collectors.toSet());
        Set<String> active = new HashSet<>();
        for (SessionDayRow r : store.sessionDays(from.toString(), to.toString())) {
            if (r.prompts() > 0 || r.edits() > 0) {
                active.add(r.day());
            }
        }
        return active.stream().filter(d -> !done.contains(d)).sorted().map(LocalDate::parse).toList();
    }

    /** @return 기록이 없어 건너뛰었으면 false */
    public boolean summarize(LocalDate day) throws IOException {
        DayDetail detail = query.day(day);
        if (detail.tasks().isEmpty()) {
            return false;
        }
        String digest = digest(detail);
        String prompt = """
                너는 개발자 업무일지 정리 담당이다. 아래는 %s(%s) 하루 동안 사용자가 Claude Code 세션들에서 요청한 내용, 수정된 파일, git 커밋이다.

                %s
                출력은 아래 형식의 JSON 하나만 낸다. 코드펜스나 다른 말은 붙이지 않는다.
                {"overview": "그날 한 일 전체를 2~3문장으로", "sessions": [{"id": "<세션 id 그대로>", "summary": "그 세션에서 한 일 1~2문장"}]}

                ---
                %s
                """.formatted(day, day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN), RULES, digest);
        JsonNode json = parseJson(claude.ask(prompt));
        String overview = json.path("overview").asText("").strip();
        if (overview.isEmpty()) {
            throw new IOException("요약 응답에 overview 가 없음");
        }
        Set<String> ids = new HashSet<>();
        detail.tasks().forEach(t -> t.sessions().stream().map(DaySession::id).forEach(ids::add));
        for (JsonNode s : json.path("sessions")) {
            String id = s.path("id").asText();
            String summary = s.path("summary").asText("").strip();
            if (ids.contains(id) && !summary.isEmpty()) {
                store.saveSessionDaySummary(id, day.toString(), summary);
            }
        }
        store.saveDaySummary(day.toString(), overview, claude.model());
        log.info("{} 하루 요약 저장", day);
        return true;
    }

    /** 너무 길면 요청 한 줄 길이를 줄여 다시 만든다 */
    String digest(DayDetail detail) {
        String digest = "";
        for (int chars : List.of(400, 200, 100)) {
            digest = DayDigest.render(detail, props.zone(), chars);
            if (digest.length() <= MAX_DIGEST_CHARS) {
                return digest;
            }
        }
        return digest.substring(0, MAX_DIGEST_CHARS) + "\n…(이하 생략)";
    }

    public static JsonNode parseJson(String text) throws IOException {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("JSON 응답이 아님: " + DayDigest.oneLine(text, 200));
        }
        return MAPPER.readTree(text.substring(start, end + 1));
    }
}
