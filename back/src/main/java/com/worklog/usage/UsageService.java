package com.worklog.usage;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;

import com.worklog.config.WorklogProperties.ModelPrice;
import com.worklog.usage.UsageStore.AggRow;

import lombok.RequiredArgsConstructor;

/** 기간 사용량을 세션×날짜·모델별로 묶어 화면 계산에 넘긴다. 비용은 조회할 때마다 현재 단가표로 다시 계산한다 */
@Service
@RequiredArgsConstructor
public class UsageService {

    private final UsageStore store;
    private final Pricing pricing;

    public Index index(LocalDate from, LocalDate to) {
        return index(from.toString(), to.toString());
    }

    public Index all() {
        return index("0000-00-00", "9999-99-99");
    }

    private Index index(String from, String to) {
        List<AggRow> rows = store.aggregate(from, to);
        Map<String, UsageTotal> bySessionDay = new HashMap<>();
        Map<AggRow, UsageTotal> perRow = new HashMap<>();
        Map<String, Pricing.Rate> rates = new HashMap<>();
        for (AggRow r : rows) {
            pricing.rate(r.model()).ifPresent(rt -> rates.put(r.model(), rt));
            UsageTotal t = total(r);
            perRow.put(r, t);
            bySessionDay.merge(r.sessionId() + "|" + r.day(), t, UsageTotal::plus);
        }
        return new Index(bySessionDay, perRow, rates);
    }

    private UsageTotal total(AggRow r) {
        ModelPrice p = pricing.price(r.model()).orElse(null);
        double cost = p == null ? 0 : pricing.cost(p, r.input(), r.output(), r.cacheRead(), r.cacheWrite5m(), r.cacheWrite1h());
        return new UsageTotal(r.responses(), r.input(), r.output(), r.cacheRead(), r.cacheWrite5m(), r.cacheWrite1h(),
                cost, p == null ? r.responses() : 0);
    }

    /** rate = 환산에 쓴 단가($/1M 토큰), 단가표에 없는 모델이면 null */
    public record ModelUsage(String model, boolean priced, UsageTotal usage, Pricing.Rate rate) {
    }

    public static final class Index {

        private final Map<String, UsageTotal> bySessionDay;
        private final Map<AggRow, UsageTotal> perRow;
        private final Map<String, Pricing.Rate> rates;

        Index(Map<String, UsageTotal> bySessionDay, Map<AggRow, UsageTotal> perRow, Map<String, Pricing.Rate> rates) {
            this.bySessionDay = bySessionDay;
            this.perRow = perRow;
            this.rates = rates;
        }

        public UsageTotal of(String sessionId, String day) {
            return bySessionDay.getOrDefault(sessionId + "|" + day, UsageTotal.ZERO);
        }

        public double cost(String sessionId, String day) {
            return of(sessionId, day).costUsd();
        }

        /** 조건에 맞는 세션들의 합계 */
        public UsageTotal total(Predicate<String> session) {
            UsageTotal t = UsageTotal.ZERO;
            for (Map.Entry<AggRow, UsageTotal> e : perRow.entrySet()) {
                if (session.test(e.getKey().sessionId())) {
                    t = t.plus(e.getValue());
                }
            }
            return t;
        }

        /** 날짜별 합계(조건에 맞는 세션만) */
        public Map<String, UsageTotal> byDay(Predicate<String> session) {
            Map<String, UsageTotal> m = new TreeMap<>();
            perRow.forEach((r, t) -> {
                if (session.test(r.sessionId())) {
                    m.merge(r.day(), t, UsageTotal::plus);
                }
            });
            return m;
        }

        public List<ModelUsage> byModel(Predicate<String> session) {
            Map<String, UsageTotal> m = new HashMap<>();
            Map<String, Boolean> priced = new HashMap<>();
            perRow.forEach((r, t) -> {
                if (session.test(r.sessionId())) {
                    m.merge(r.model(), t, UsageTotal::plus);
                    priced.merge(r.model(), t.unpriced() == 0, Boolean::logicalAnd);
                }
            });
            return m.entrySet().stream()
                    .map(e -> new ModelUsage(e.getKey(), priced.get(e.getKey()), e.getValue(), rates.get(e.getKey())))
                    .sorted(Comparator.comparingDouble((ModelUsage u) -> u.usage().costUsd()).reversed()
                            .thenComparing(u -> -u.usage().responses()))
                    .toList();
        }
    }
}
