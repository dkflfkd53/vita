package com.worklog.report;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 리포트 기간. week = ISO 주(월~일) "2026-W44", month = "2026-10", year = "2026" */
public record Period(String kind, String key, LocalDate from, LocalDate to) {

    private static final Pattern WEEK = Pattern.compile("(\\d{4})-W(\\d{1,2})");
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("M/d");

    public static Period of(String kind, String key) {
        return switch (kind) {
            case "week" -> {
                Matcher m = WEEK.matcher(key);
                if (!m.matches()) {
                    throw new IllegalArgumentException("주 형식은 2026-W44");
                }
                LocalDate monday = LocalDate.of(Integer.parseInt(m.group(1)), 1, 4)
                        .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, Integer.parseInt(m.group(2)))
                        .with(DayOfWeek.MONDAY);
                yield new Period(kind, key, monday, monday.plusDays(6));
            }
            case "month" -> {
                YearMonth ym = YearMonth.parse(key);
                yield new Period(kind, key, ym.atDay(1), ym.atEndOfMonth());
            }
            case "year" -> {
                if (!key.matches("\\d{4}")) {
                    throw new IllegalArgumentException("연 형식은 2026");
                }
                int y = Integer.parseInt(key);
                yield new Period(kind, key, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31));
            }
            default -> throw new IllegalArgumentException("kind 는 week·month·year");
        };
    }

    /** 이 날짜가 든 같은 종류의 기간 */
    public static Period containing(String kind, LocalDate date) {
        return switch (kind) {
            case "week" -> of(kind, weekKey(date));
            case "month" -> of(kind, YearMonth.from(date).toString());
            case "year" -> of(kind, String.valueOf(date.getYear()));
            default -> throw new IllegalArgumentException("kind 는 week·month·year");
        };
    }

    /** 바로 앞·뒤 같은 종류의 기간 */
    public Period previous() {
        return containing(kind, from.minusDays(1));
    }

    public Period next() {
        return containing(kind, to.plusDays(1));
    }

    public static String weekKey(LocalDate date) {
        return "%d-W%02d".formatted(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    public String label() {
        if ("week".equals(kind)) {
            // "10월 5주차" — 그 주 목요일이 속한 달의 몇 번째 목요일인지(회사 주차 관례).
            // 주말은 일하지 않아 표기는 월~금. 집계 기간(from~to)은 그대로 7일이라 주말 기록도 합계엔 들어간다
            LocalDate thu = from.plusDays(3);
            return "%d년 %d월 %d주차 (%s~%s)".formatted(thu.getYear(), thu.getMonthValue(), (thu.getDayOfMonth() - 1) / 7 + 1,
                    from.format(MD), from.plusDays(4).format(MD));
        }
        if ("year".equals(kind)) {
            return "%d년".formatted(from.getYear());
        }
        return "%d년 %d월".formatted(from.getYear(), from.getMonthValue());
    }

    public String kindLabel() {
        return switch (kind) {
            case "week" -> "주간";
            case "year" -> "연간";
            default -> "월간";
        };
    }
}
