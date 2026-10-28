package com.worklog.usage;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.worklog.config.WorklogProperties;
import com.worklog.config.WorklogProperties.ModelPrice;

import lombok.RequiredArgsConstructor;

/** application.yml 의 worklog.pricing 으로 API 환산 비용을 낸다. 캐시 쓰기 = 입력 단가 × 1.25(5분) / × 2(1시간) */
@Component
@RequiredArgsConstructor
public class Pricing {

    static final double WRITE_5M = 1.25;
    static final double WRITE_1H = 2.0;

    private final WorklogProperties props;

    /** 정확히 같은 키, 없으면 모델 id 가 그 키로 시작하는 것 중 가장 긴 키(claude-haiku-4-5-20251001 → claude-haiku-4-5) */
    public Optional<ModelPrice> price(String model) {
        Map<String, ModelPrice> table = props.pricing() == null ? Map.of() : props.pricing();
        ModelPrice exact = table.get(model);
        if (exact != null) {
            return Optional.of(exact);
        }
        return table.entrySet().stream()
                .filter(e -> model.startsWith(e.getKey() + "-"))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue);
    }

    /** 화면에 단가를 그대로 보이려고 — 캐시 쓰기는 입력 단가에서 계산한 값 */
    public Optional<Rate> rate(String model) {
        return price(model).map(p -> new Rate(p.input(), p.output(), p.cacheRead(), p.input() * WRITE_5M, p.input() * WRITE_1H));
    }

    /** $/1M 토큰 */
    public record Rate(double input, double output, double cacheRead, double cacheWrite5m, double cacheWrite1h) {
    }

    public double cost(ModelPrice p, long input, long output, long cacheRead, long write5m, long write1h) {
        return (input * p.input() + output * p.output() + cacheRead * p.cacheRead()
                + write5m * p.input() * WRITE_5M + write1h * p.input() * WRITE_1H) / 1_000_000.0;
    }
}
