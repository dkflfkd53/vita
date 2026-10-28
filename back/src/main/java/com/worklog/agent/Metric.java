package com.worklog.agent;

import java.util.Arrays;
import java.util.Optional;

/**
 * 개선 효과를 재는 지표. better = lower 면 낮을수록 좋음, none 은 참고용(목표로 삼지 않음).
 * 제안(agent_insight.metric)은 이 키 중 하나를 가리킨다.
 */
public enum Metric {
    ERROR_RATE("error_rate", "도구 실패율", "%", true, "실패한 도구 호출 ÷ 끝난 도구 호출 (거절 제외)"),
    CORRECTION_RATE("correction_rate", "정정 요청", "/100요청", true, "\"아니·다시·안 돼\" 같은 정정·불만 신호가 있는 요청 수 ÷ 요청 100개 (낱말로 추정)"),
    INTERRUPT_RATE("interrupt_rate", "중단", "/100요청", true, "작업 도중 Esc 로 멈춘 횟수 ÷ 요청 100개"),
    WAIT_SHARE("wait_share", "대기 비중", "%", true, "승인 대기(추정) + 질문 응답 + 다음 요청까지 걸린 시간 ÷ 작업 시간"),
    TOOL_SHARE("tool_share", "도구 실행 비중", "%", true, "도구 실행 시간 ÷ Claude 턴 시간 — 빌드·테스트·조회 명령에 쓴 몫"),
    REWORK("rework", "파일당 수정", "회", true, "같은 세션에서 한 파일을 평균 몇 번 고쳤나 — 높으면 고쳤다 되돌리기가 많다"),
    COMPACT_RATE("compact_rate", "컨텍스트 압축", "/10시간", true, "작업 10시간당 자동·수동 압축 횟수 — 세션이 너무 길다는 신호"),
    COST_PER_HOUR("cost_per_hour", "시간당 비용", "$", true, "API 환산 비용 ÷ 작업 시간"),
    CACHE_WRITE_SHARE("cache_write_share", "캐시 쓰기 비용", "%", true, "캐시 쓰기 비용 ÷ 전체 비용 — 문맥을 새로 쌓는 데 든 몫"),
    SUBAGENT_SHARE("subagent_share", "서브에이전트 비용", "%", false, "서브에이전트가 쓴 비용 ÷ 전체 비용 (참고)"),
    AVG_TURN("avg_turn", "평균 턴 길이", "분", false, "Claude 가 한 번 요청받고 멈출 때까지 평균 (참고)");

    public final String key;
    public final String label;
    public final String unit;
    public final boolean lowerIsBetter;
    public final String description;

    Metric(String key, String label, String unit, boolean lowerIsBetter, String description) {
        this.key = key;
        this.label = label;
        this.unit = unit;
        this.lowerIsBetter = lowerIsBetter;
        this.description = description;
    }

    public static Optional<Metric> of(String key) {
        return Arrays.stream(values()).filter(m -> m.key.equals(key)).findFirst();
    }
}
