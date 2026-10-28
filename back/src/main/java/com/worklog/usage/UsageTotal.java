package com.worklog.usage;

/** 토큰 합계 + 환산 비용. unpriced = 단가표에 없는 모델의 응답 수(비용에서 빠짐) */
public record UsageTotal(int responses, long input, long output, long cacheRead, long cacheWrite5m,
                         long cacheWrite1h, double costUsd, int unpriced) {

    public static final UsageTotal ZERO = new UsageTotal(0, 0, 0, 0, 0, 0, 0, 0);

    public UsageTotal plus(UsageTotal o) {
        return new UsageTotal(responses + o.responses, input + o.input, output + o.output, cacheRead + o.cacheRead,
                cacheWrite5m + o.cacheWrite5m, cacheWrite1h + o.cacheWrite1h, costUsd + o.costUsd, unpriced + o.unpriced);
    }
}
